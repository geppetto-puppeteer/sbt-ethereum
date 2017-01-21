package com.mchange.sc.v1

import sbt._
import sbt.Keys._

import scala.io.{Codec,Source}
import scala.collection._
import scala.concurrent.{Await,ExecutionContext,Future}
import scala.concurrent.duration.Duration
import scala.math.max
import scala.util.Failure
import scala.util.matching.Regex.Match
import scala.annotation.tailrec

import java.io._

import com.mchange.sc.v1.log.MLevel._
import com.mchange.sc.v2.concurrent._

import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.failable.fail
import com.mchange.sc.v2.literal._

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v1.consuela.ethereum.{jsonrpc20,wallet,EthAddress,EthHash,EthPrivateKey,EthTransaction}
import com.mchange.sc.v1.consuela.ethereum.jsonrpc20.{Abi,ClientTransactionReceipt,MapStringCompilationContractFormat}
import com.mchange.sc.v1.consuela.ethereum.encoding.RLP
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations.Denomination // XXX: Ick! Refactor this in consuela!

import play.api.libs.json._


package object sbtethereum {
  private implicit lazy val logger = mlogger( "com.mchange.sc.v1.sbtethereum.package" )

  private val SEP = Option( System.getProperty("line.separator") ).getOrElse( "\n" )

  abstract class SbtEthereumException( msg : String, cause : Throwable = null ) extends Exception( msg, cause )

  final class NoSolidityCompilerException( msg : String ) extends SbtEthereumException( msg )
  final class DatabaseVersionException( msg : String ) extends SbtEthereumException( msg )
  final class ContractUnknownException( msg : String ) extends SbtEthereumException( msg )
  final class UnparsableFileException( msg : String, line : Int, col : Int ) extends Exception( msg + s" [${line}:${col}]" )

  private val SolFileRegex = """(.+)\.sol""".r

  // XXX: hardcoded
  private val SolidityWriteBufferSize = 1024 * 1024; //1 MiB

  // XXX: geth seems not to be able to validate some subset of the signatures that we validate as good (and homestead compatible)
  //      to work around this, we just retry a few times when we get "Invalid sender" errors on sending a signed transaction
  val InvalidSenderRetries = 10

  val EmptyAbi = Abi.Definition.empty

  private def doWithJsonClient[T]( log : sbt.Logger, jsonRpcUrl : String )( operation : jsonrpc20.Client => T )( implicit ec : ExecutionContext ) : T = {
    try {
      borrow( new jsonrpc20.Client.Simple( new URL( jsonRpcUrl ) ) )( operation )
    } catch {
      case e : java.net.ConnectException => {
        log.error( s"Failed to connect to JSON-RPC client at '${jsonRpcUrl}': ${e}" )
        throw e
      }
    }
  }

  sealed trait IrreconcilableUpdatePolicy;
  final case object UseOriginal        extends IrreconcilableUpdatePolicy
  final case object UseNewer           extends IrreconcilableUpdatePolicy
  final case object PrioritizeOriginal extends IrreconcilableUpdatePolicy
  final case object PrioritizeNewer    extends IrreconcilableUpdatePolicy
  final case object Throw              extends IrreconcilableUpdatePolicy

  final class ContractMergeException( message : String, cause : Throwable = null ) extends Exception( message, cause )

  // some formatting functions for ascii tables
  def emptyOrHex( str : String ) = if (str == null) "" else s"0x$str"
  def blankNull( str : String ) = if (str == null) "" else str
  def span( len : Int ) = (0 until len).map(_ => "-").mkString

  def fileToString( srcFile : File ) : String = {
    borrow( Source.fromFile( srcFile )(Codec.UTF8) )( _.close() ){ source =>
      source.foldLeft("")( _ + _ )
    }
  }

  private def urlToSourceFile( url : URL ) : SourceFile = {
    val urlConn = url.openConnection()
    val lastMod = urlConn.getLastModified
    val contents = borrow( Source.fromInputStream( new BufferedInputStream( urlConn.getInputStream() ) )(Codec.UTF8) )( _.close ){ source =>
      source.foldLeft("")( _ + _ )
    }
    SourceFile( contents, lastMod )
  }



  // for eventual resolution of github URL imports
  private val GithubUrlRegex = """^(?:https?\/\/)?(\S*github\S*.com)(\/\S*)$""".r
  private val BlobRegex      = """\/blob""".r

  def resolveToRawGithubUrl( mbRegularGithubUrl : String ) : Option[String] = {
    mbRegularGithubUrl match {
      case GithubUrlRegex( host, path ) => Some( s"""https://raw.githubusercontent.com${BlobRegex.replaceAllIn(path,"")}""" )
      case _                            => None
    }
  }


  /*
  def resolveImport( sourceDirs : Seq[File], importBody : String ) : Failable[( String, Long )] = importBody match {
    case GoodImportBodyRegex( imported ) => {
      val key = StringLiteral.parsePermissiveStringLiteral( imported ).parsed
      sourceFileForKey( sourceDirs, key )
    }
    case unkey => fail( s"""Unsupported import format: '${unkey}' [sbt-ethereum supports only simple 'import "<filespec>"', without 'from' or 'as' clauses.]""" )
  }
   */ 

  private object SourceFile {
    def apply( parentDir : File, filePath : String ) : SourceFile = {
      val f = new File( parentDir, filePath )
      val contents = fileToString(f)
      val lastMod = f.lastModified
      SourceFile( contents, lastMod )
    }
    def apply( url : URL ) : SourceFile = urlToSourceFile( url )

    val oldAndEmpty = SourceFile("", Long.MinValue)
  }
  private case class SourceFile( text : String, lastModified : Long )

  private def sourceFileForKey( sourceDirs : Seq[File], key : String ) : Failable[SourceFile] = {
    def resolveFile( parentDir : File, filePath : String ) = fileToString( new File( parentDir, filePath ) )
    def handleNext( cur : Failable[SourceFile], nextSourceDir : File ) : Failable[SourceFile] = {
      cur orElse Failable( SourceFile( nextSourceDir, key ) )
    }
    val defaultFail : Failable[SourceFile] = fail( s"""Could not resolve file for '${key}' in any of source dirs '${sourceDirs.mkString(":")}'""" )
    sourceDirs.foldLeft( defaultFail )( handleNext )
  }

  @tailrec
  private def loadResolveSourceFile( allSourceDirs : Seq[File], key : String, remainingSourceDirs : Seq[File] ) : Failable[SourceFile] = {
    if ( remainingSourceDirs.isEmpty ){
      fail( s"""Could not resolve file for '${key}', checked source dirs: '${allSourceDirs.mkString(", ")}'""" )
    } else {
      val nextSrcDir = remainingSourceDirs.head
      def premessage( from : String = nextSrcDir.toString ) = s"Failed to load '${key}' from '${from}': "
      val mbGithubUrl = resolveToRawGithubUrl( key )
      val fsource = mbGithubUrl.fold( Failable( SourceFile( nextSrcDir, key ) ).xdebug( premessage() ) ) { url =>
        Failable( SourceFile( new URL( url ) ) ).xdebug( premessage( "github" ) ) orElse
        Failable( SourceFile( nextSrcDir, key ) ).xdebug( premessage() )
      }
      // we're going to need a more robust way of parsing out uncommented, unquoted imports
      //val fsource = fsourceRaw.map( sf => sf.copy( text = removeComments( sf.text ) ) )
      if ( fsource.isFailed ) {
        loadResolveSourceFile( allSourceDirs, key, remainingSourceDirs.tail )
      } else {
        substituteImports( allSourceDirs, fsource.get )
      }
    }
  }

  private val AnyCommentRegex = {
    val DoubleSlashComments = """\/\/.*?(?=[\r\n])"""
    val CStyleComment       = """\/\*.*?\*\/"""
    s"""(?:${DoubleSlashComments}|${CStyleComment})""".r
  }

  private def removeComments( src : String ) : String = AnyCommentRegex.replaceAllIn(src,"")

  private def loadResolveSourceFile( allSourceDirs : Seq[File], key : String ) : Failable[SourceFile] = loadResolveSourceFile( allSourceDirs, key, allSourceDirs )

  private def loadResolveSourceFile( file : File ) : Failable[SourceFile] = loadResolveSourceFile( file.getParentFile :: Nil, file.getName )


  private val ImportRegex = """import\s+(.*)\;""".r
  private val GoodImportBodyRegex = """\s*(\042.*?\042)\s*""".r

  private def substituteImports( allSourceDirs : Seq[File], input : SourceFile ) : Failable[SourceFile] = {

    var lastModified : Long = input.lastModified

    val ( normalized, tcq ) = TextCommentQuote.parse( input.text )
     
    def replaceMatch( m : Match ) : String = {
      if ( tcq.quote.containsPoint( m.start ) ) {          // if the word import is in a quote
        m.group(0)                                         //   replace the match with itself
      } else if ( tcq.comment.containsPoint( m.start ) ) { // if the word import is in a comment
        m.group(0)                                         //   replace the match with itself
      } else {                                             // otherwise, do the replacement
        m.group(1) match {
          case GoodImportBodyRegex( imported ) => {
            val key = StringLiteral.parsePermissiveStringLiteral( imported ).parsed
            val fimport = loadResolveSourceFile( allSourceDirs, key )
            val sourceFile = fimport.get // throw the Exception if resolution failed
            lastModified = max( lastModified, sourceFile.lastModified )
            sourceFile.text
          }
          case unkey => throw new Exception( s"""Unsupported import format: '${unkey}' [sbt-ethereum supports only simple 'import "<filespec>"', without 'from' or 'as' clauses.]""" )
        }
      }
    }

    val resolved = ImportRegex.replaceAllIn( normalized, replaceMatch _ )

    succeed( SourceFile( resolved, lastModified ) )
  }

  private val SolidityFileBadFirstChars = ".#~"

  def goodSolidityFileName( simpleName : String ) : Boolean =  simpleName.endsWith(".sol") && SolidityFileBadFirstChars.indexOf( simpleName.head ) < 0

  private [sbtethereum] def doCompileSolidity( log : sbt.Logger, jsonRpcUrl : String, includeSourceDirs : Seq[File], solSourceDir : File, solDestDir : File )( implicit ec : ExecutionContext ) : Unit = {
    def solToJson( filename : String ) : String = filename match {
      case SolFileRegex( base ) => base + ".json"
    }

    // TODO XXX: check imported files as well!
    def changed( destinationFile : File, sourceFile : SourceFile ) : Boolean = (! destinationFile.exists) || (sourceFile.lastModified > destinationFile.lastModified() )

    def waitForFiles[T]( files : Iterable[(File,Future[T])], errorMessage : Int => String ) : Unit = {
      val labeledFailures = awaitAndGatherLabeledFailures( files )
      val failureCount = labeledFailures.size
      if ( failureCount > 0 ) {
        log.error( errorMessage( failureCount ) )
        labeledFailures.foreach { 
          case ( file, jf : jsonrpc20.Exception ) => log.error( s"File: ${file.getAbsolutePath}${SEP}${jf.message}" )
          case ( file, other                    ) => log.error( s"File: ${file.getAbsolutePath}${SEP}${other.toString}" )
        }
        throw labeledFailures.head._2
      }
    }

    val allSourceDirs = solSourceDir +: includeSourceDirs

    doWithJsonClient( log, jsonRpcUrl ){ client =>
      solDestDir.mkdirs()
      val files = (solSourceDir ** "*.sol").get.filter( file => goodSolidityFileName( file.getName ) )

      val filePairs = files.map( file => ( file, loadResolveSourceFile( file ).get, new File( solDestDir, solToJson( file.getName() ) ) ) ) // (sourceFile, destinationFile), exception if file can't load
      val compileFiles = filePairs.filter{ case ( file, sourceFile, destFile ) => changed( destFile, sourceFile ) }

      val cfl = compileFiles.length
      if ( cfl > 0 ) {
        val mbS = if ( cfl > 1 ) "s" else ""
        log.info( s"Compiling ${compileFiles.length} Solidity source${mbS} to ${solDestDir}..." )

        val compileLabeledFuts = compileFiles.map { case ( file, sourceFile, destFile ) =>
          val code = sourceFile.text
          // println( code )
          file -> client.eth.compileSolidity( code ).map( result => ( destFile, result ) )
        }
        waitForFiles( compileLabeledFuts, count => s"compileSolidity failed. [${count} failures]" )

        // if we're here, all compilations succeeded
        val destFileResultPairs = compileLabeledFuts.map {
          case ( _, fut ) => fut.value.get.get
        }

        val writerLabeledFuts = destFileResultPairs.map {
          case ( destFile, result ) => {
            destFile -> Future {
              borrow( new OutputStreamWriter( new BufferedOutputStream( new FileOutputStream( destFile ), SolidityWriteBufferSize ), Codec.UTF8.charSet ) ){ writer =>
                writer.write( Json.stringify( Json.toJson ( result ) ) )
              }
            }
          }
        }
        waitForFiles( writerLabeledFuts, count => s"Failed to write the output of some compilations. [${count} failures]" )
      }
    }
  }

  private [sbtethereum] def doGetBalance( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getBalance( address, blockNumber ), Duration.Inf ) )
  }

  private [sbtethereum] def doCodeForAddress( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : immutable.Seq[Byte] = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getCode( address, blockNumber ), Duration.Inf ) )
  }

  case class EthValue( wei : BigInt, denominated : BigDecimal, denomination : Denomination )

  private [sbtethereum] def doPrintingGetBalance(
    log          : sbt.Logger,
    jsonRpcUrl   : String,
    address      : EthAddress,
    blockNumber  : jsonrpc20.Client.BlockNumber,
    denomination : Denomination
  )( implicit ec : ExecutionContext ) : EthValue = {
    import jsonrpc20.Client.BlockNumber._

    val wei = doGetBalance( log, jsonRpcUrl, address, blockNumber )( ec )
    val out = EthValue( wei, denomination.fromWei( wei ), denomination )
    val msg = blockNumber match {
      case Earliest       => s"${out.denominated} ${denomination.unitName} (at the earliest available block, address 0x${address.hex})"
      case Latest         => s"${out.denominated} ${denomination.unitName} (as of the latest incorporated block, address 0x${address.hex})"
      case Pending        => s"${out.denominated} ${denomination.unitName} (including currently pending transactions, address 0x${address.hex})"
      case Quantity( bn ) => s"${out.denominated} ${denomination.unitName} (at block #${bn}, address 0x${address.hex})"
    }
    log.info(msg)
    out
  }

  private [sbtethereum] def doEthCallEphemeral(
    log         : sbt.Logger,
    jsonRpcUrl  : String,
    from        : Option[EthAddress],
    to          : EthAddress,
    gas         : Option[BigInt],
    gasPrice    : Option[BigInt],
    value       : Option[BigInt],
    data        : Option[Seq[Byte]],
    blockNumber : jsonrpc20.Client.BlockNumber
  )( implicit ec : ExecutionContext ) : immutable.Seq[Byte] = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.call( from, Some(to), gas, gasPrice, value, data, blockNumber), Duration.Inf ) )
  }

  private [sbtethereum] def doGetDefaultGasPrice( log : sbt.Logger, jsonRpcUrl : String )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.gasPrice(), Duration.Inf ) )
  }

  private [sbtethereum] def doGetTransactionCount( log : sbt.Logger, jsonRpcUrl : String, address : EthAddress, blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.getTransactionCount( address, blockNumber ), Duration.Inf ) )
  }

  private [sbtethereum] def doEstimateGas( log : sbt.Logger, jsonRpcUrl : String, from : Option[EthAddress], to : Option[EthAddress], data : Seq[Byte], blockNumber : jsonrpc20.Client.BlockNumber )( implicit ec : ExecutionContext ) : BigInt = {
    doWithJsonClient( log, jsonRpcUrl )( client => Await.result( client.eth.estimateGas( from = from, to = to, data = Some(data) ), Duration.Inf ) )
  }

  private [sbtethereum] def rounded( bd : BigDecimal ) = bd.round( bd.mc ) // work around absence of default rounded method in scala 2.10 BigDecimal

  private [sbtethereum] def markupEstimateGas( log : sbt.Logger, jsonRpcUrl : String, from : Option[EthAddress], to : Option[EthAddress], data : Seq[Byte], blockNumber : jsonrpc20.Client.BlockNumber, markup : Double )( implicit ec : ExecutionContext ) : BigInt = {
    val rawEstimate = doEstimateGas( log, jsonRpcUrl, from, to, data, blockNumber )( ec )
    rounded(BigDecimal(rawEstimate) * BigDecimal(1 + markup)).toBigInt
  }

  private [sbtethereum] def findPrivateKey( log : sbt.Logger, mbGethWallet : Option[wallet.V3], credential : String ) : EthPrivateKey = {
    mbGethWallet.fold {
      log.info( "No wallet available. Trying passphrase as hex private key." )
      EthPrivateKey( credential )
    }{ gethWallet =>
      try {
        wallet.V3.decodePrivateKey( gethWallet, credential )
      } catch {
        case v3e : wallet.V3.Exception => {
          log.warn("Credential is not correct geth wallet passphrase. Trying as hex private key.")
          EthPrivateKey( credential )
        }
      }
    }
  }

  private [sbtethereum] def doSignSendTransaction( log : sbt.Logger, jsonRpcUrl : String, signer : EthPrivateKey, unsigned : EthTransaction.Unsigned )( implicit ec : ExecutionContext ) : EthHash = {
    doWithJsonClient( log, jsonRpcUrl ){ client =>
      val signed = unsigned.sign( signer )
      val hash = Await.result( client.eth.sendSignedTransaction( signed ), Duration.Inf )
      Repository.logTransaction( signed, hash )
      hash
    }
  }

  private [sbtethereum] def awaitTransactionReceipt(
    log : sbt.Logger,
    jsonRpcUrl : String,
    transactionHash : EthHash,
    pollSeconds : Int,
    maxPollAttempts : Int
  )( implicit ec : ExecutionContext ) : Option[ClientTransactionReceipt] = {
    doWithJsonClient( log, jsonRpcUrl ){ client =>
      def doPoll( attemptNum : Int ) : Option[ClientTransactionReceipt] = {
        val mbReceipt = Await.result( client.eth.getTransactionReceipt( transactionHash ), Duration.Inf )
        ( mbReceipt, attemptNum ) match {
          case ( None, num ) if ( num < maxPollAttempts ) => {
            log.info(s"Receipt for transaction '0x${transactionHash.bytes.hex}' not yet available, will try again in ${pollSeconds} seconds. Attempt ${attemptNum + 1}/${maxPollAttempts}.")
            Thread.sleep( pollSeconds * 1000 )
            doPoll( num + 1 )
          }
          case ( None, _ ) => {
            log.warn(s"After ${maxPollAttempts} attempts (${(maxPollAttempts - 1) * pollSeconds} seconds), no receipt has yet been received for transaction '0x${transactionHash.bytes.hex}'.")
            None
          }
          case ( Some( receipt ), _ ) => {
            log.info(s"Receipt received for transaction '0x${transactionHash.bytes.hex}':\n${receipt}")
            mbReceipt
          }
        }
      }
      doPoll( 0 )
    }
  }

  private final val CantReadInteraction = "InteractionService failed to read"

  private [sbtethereum] def readConfirmCredential(  log : sbt.Logger, is : InteractionService, readPrompt : String, confirmPrompt: String = "Please retype to confirm: ", maxAttempts : Int = 3, attempt : Int = 0 ) : String = {
    if ( attempt < maxAttempts ) {
      val credential = is.readLine( readPrompt, mask = true ).getOrElse( throw new Exception( CantReadInteraction ) )
      val confirmation = is.readLine( confirmPrompt, mask = true ).getOrElse( throw new Exception( CantReadInteraction ) )
      if ( credential == confirmation ) {
        credential
      } else {
        log.warn("Entries did not match! Retrying.")
        readConfirmCredential( log, is, readPrompt, confirmPrompt, maxAttempts, attempt + 1 )
      }
    } else {
      throw new Exception( s"After ${attempt} attempts, provided credential could not be confirmed. Bailing." )
    }
  }

  private def parseAbi( abiString : String ) = Json.parse( abiString ).as[Abi.Definition]

  private [sbtethereum] def readAddressAndAbi( log : sbt.Logger, is : InteractionService ) : ( EthAddress, Abi.Definition ) = {
    val address = EthAddress( is.readLine( "Contract address in hex: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
    val abi = parseAbi( is.readLine( "Contract ABI: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) ) )
    ( address, abi )
  }

  private [sbtethereum] def readV3Wallet( is : InteractionService ) : wallet.V3 = {
    val jsonStr = is.readLine( "V3 Wallet JSON: ", mask = false ).getOrElse( throw new Exception( CantReadInteraction ) )
    val jsv = Json.parse( jsonStr )
    wallet.V3( jsv.as[JsObject] )
  }

  private [sbtethereum] def readCredential( is : InteractionService, address : EthAddress ) : String = {
    is.readLine(s"Enter passphrase or hex private key for address '0x${address.hex}': ", mask = true).getOrElse(throw new Exception("Failed to read a credential")) // fail if we can't get a credential
  }

  private [sbtethereum] def abiForAddress( address : EthAddress, defaultNotInDatabase : => Abi.Definition, defaultNoAbi : => Abi.Definition ) : Abi.Definition = {
    val mbDeployedContractInfo = Repository.Database.deployedContractInfoForAddress( address ).get // throw an Exception if there's a database problem
    mbDeployedContractInfo.fold( defaultNotInDatabase ) { deployedContractInfo =>
      deployedContractInfo.abiDefinition.fold( defaultNoAbi )( parseAbi )
    }
  }

  private [sbtethereum] def abiForAddress( address : EthAddress ) : Abi.Definition = {
    def defaultNotInDatabase = throw new ContractUnknownException( s"A contract at address ${address.hex} is not known in the sbt-ethereum repository." )
    def defaultNoAbi = throw new ContractUnknownException( s"The contract at address ${address.hex} does not have an ABI associated with it in the sbt-ethereum repository." )
    abiForAddress( address, defaultNotInDatabase, defaultNoAbi )
  }

  private [sbtethereum] def abiForAddressOrEmpty( address : EthAddress ) : Abi.Definition = {
    abiForAddress( address, EmptyAbi, EmptyAbi )
  }


  private [sbtethereum] def unknownWallet( loadDirs : Seq[File] ) : Nothing = {
    val dirs = loadDirs.map( _.getAbsolutePath() ).mkString(", ")
    throw new Exception( s"Could not find V3 wallet for the specified address in the specified keystore directories: ${dirs}}" )
  }
}


