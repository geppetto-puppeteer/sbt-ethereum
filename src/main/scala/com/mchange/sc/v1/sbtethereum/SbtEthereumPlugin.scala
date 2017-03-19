package com.mchange.sc.v1.sbtethereum

import Parsers._

import sbt._
import sbt.Keys._
import sbt.plugins.{JvmPlugin,InteractionServicePlugin}
import sbt.Def.Initialize
import sbt.InteractionServiceKeys.interactionService

import sbinary._
import sbinary.DefaultProtocol._
import SBinaryFormats._

import java.io.{BufferedInputStream,File,FileInputStream,FilenameFilter}
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.atomic.AtomicReference

import play.api.libs.json.Json

import com.mchange.sc.v2.failable._
import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.consuela._
import com.mchange.sc.v1.consuela.ethereum._
import jsonrpc20.{Abi,ClientTransactionReceipt,MapStringCompilationContractFormat}
import specification.Denominations

import com.mchange.sc.v1.consuela.ethereum.specification.Types.Unsigned256
import com.mchange.sc.v1.consuela.ethereum.specification.Fees.BigInt._
import com.mchange.sc.v1.consuela.ethereum.specification.Denominations._
import com.mchange.sc.v1.consuela.ethereum.ethabi.{abiFunctionForFunctionNameAndArgs,callDataForAbiFunction,decodeReturnValuesForFunction,DecodedReturnValue,Encoder}
import scala.collection._

// XXX: provisionally, for now... but what sort of ExecutionContext would be best when?
import scala.concurrent.ExecutionContext.Implicits.global

object SbtEthereumPlugin extends AutoPlugin {

  private val EthAddressSystemProperty      = "eth.address"
  private val EthAddressEnvironmentVariable = "ETH_ADDRESS"

  // not lazy. make sure the initialization banner is emitted before any tasks are executed
  // still, generally we should try to log through sbt loggers
  private implicit val logger = mlogger( this )

  private implicit val UnlockedKey = new AtomicReference[Option[(EthAddress,EthPrivateKey)]]( None )

  private val BufferSize = 4096

  private val PollSeconds = 15

  private val PollAttempts = 9

  private val Zero = BigInt(0)


  private val Zero256 = Unsigned256( 0 )

  private val ZeroEthAddress = (0 until 40).map(_ => "0").mkString("")

  object autoImport {

    // settings

    val ethAddress = settingKey[String]("The address from which transactions will be sent")

    val ethBlockchainId = settingKey[String]("A name for the network represented by ethJsonRpcUrl (e.g. 'mainnet', 'morden', 'ropsten')")

    val ethEntropySource = settingKey[SecureRandom]("The source of randomness that will be used for key generation")

    val ethIncludeLocations = settingKey[Seq[String]]("Directories or URLs that should be searched to resolve import directives, besides the source directory itself")

    val ethGasOverrides = settingKey[Map[String,BigInt]]("Map of contract names to gas limits for contract creation transactions, overriding automatic estimates")

    val ethGasMarkup = settingKey[Double]("Fraction by which automatically estimated gas limits will be marked up (if not overridden) in setting contract creation transaction gas limits")

    val ethGasPriceMarkup = settingKey[Double]("Fraction by which automatically estimated gas price will be marked up (if not overridden) in executing transactions")

    val ethKeystoresV3 = settingKey[Seq[File]]("Directories from which V3 wallets can be loaded")

    val ethKnownStubAddresses = settingKey[immutable.Map[String,immutable.Set[String]]]("Names of stubs that might be generated in compilation mapped to addresses known to conform to their ABIs.")

    val ethJsonRpcUrl = settingKey[String]("URL of the Ethereum JSON-RPC service build should work with")

    val ethTargetDir = settingKey[File]("Location in target directory where ethereum artifacts will be placed")

    val ethSoliditySource = settingKey[File]("Solidity source code directory")

    val ethSolidityDestination = settingKey[File]("Location for compiled solidity code and metadata")

    val ethWalletV3ScryptN = settingKey[Int]("The value to use for parameter N when generating Scrypt V3 wallets")

    val ethWalletV3ScryptR = settingKey[Int]("The value to use for parameter R when generating Scrypt V3 wallets")

    val ethWalletV3ScryptP = settingKey[Int]("The value to use for parameter P when generating Scrypt V3 wallets")

    val ethWalletV3ScryptDkLen = settingKey[Int]("The derived key length parameter used when generating Scrypt V3 wallets")

    val ethWalletV3Pbkdf2C = settingKey[Int]("The value to use for parameter C when generating pbkdf2 V3 wallets")

    val ethWalletV3Pbkdf2DkLen = settingKey[Int]("The derived key length parameter used when generating pbkdf2 V3 wallets")

    // tasks

    val xethLoadAbiFor = inputKey[Abi.Definition]("Finds the ABI for a contract address, if known")

    val ethAliasDrop = inputKey[Unit]("Drops an alias for an ethereum address from the sbt-ethereum repository database.")

    val ethAliasList = inputKey[Unit]("Lists aliases for ethereum addresses that can be used in place of the hex address in many tasks.")

    val ethAliasSet = inputKey[Unit]("Defines (or redefines) an alias for an ethereum address that can be used in place of the hex address in many tasks.")

    val ethBalance = inputKey[BigDecimal]("Computes the balance in ether of the address set as 'ethAddress'")

    val ethBalanceFor = inputKey[BigDecimal]("Computes the balance in ether of a given address")

    val ethBalanceInWei = inputKey[BigInt]("Computes the balance in wei of the address set as 'ethAddress'")

    val ethBalanceInWeiFor = inputKey[BigInt]("Computes the balance in wei of a given address")

    val ethCallConstant = inputKey[(Abi.Function,immutable.Seq[DecodedReturnValue])]("Makes a call to a constant function, consulting only the local copy of the blockchain. Burns no Ether. Returns the latest available result.")

    val ethCompilationsCull = taskKey[Unit]("Removes never-deployed compilations from the repository database.")

    val ethCompilationsInspect = inputKey[Unit]("Dumps to the console full information about a compilation, based on either a code hash or contract address")

    val ethCompilationsList = taskKey[Unit]("Lists summary information about compilations known in the repository")

    val ethDefaultGasPrice = taskKey[BigInt]("Finds the current default gas price")

    val ethDeployOnly = inputKey[Option[ClientTransactionReceipt]]("Deploys the specified named contract")

    val xethFindCacheAliasesIfAvailable = taskKey[Option[immutable.SortedMap[String,EthAddress]]]("Finds and caches address aliases, if they are available. Triggered by ethAliasSet and ethAliasDrop.")

    val xethFindCacheOmitDupsCurrentCompilations = taskKey[immutable.Map[String,jsonrpc20.Compilation.Contract]]("Finds and caches compiled, deployable contract names, omitting ambiguous duplicates. Triggered by ethSolidityCompile")

    val ethGasPrice = taskKey[BigInt]("Finds the current gas price, including any overrides or gas price markups")

    val ethGenKeyPair = taskKey[EthKeyPair]("Generates a new key pair, using ethEntropySource as a source of randomness")

    val ethKeystoreCreateWalletV3Pbkdf2 = taskKey[wallet.V3]("Generates a new pbkdf2 V3 wallet, using ethEntropySource as a source of randomness")

    val ethKeystoreCreateWalletV3Scrypt = taskKey[wallet.V3]("Generates a new scrypt V3 wallet, using ethEntropySource as a source of randomness")

    val ethKeystoreCreateWalletV3 = taskKey[wallet.V3]("Generates a new V3 wallet, using ethEntropySource as a source of randomness")

    val ethInvoke = inputKey[Option[ClientTransactionReceipt]]("Calls a function on a deployed smart contract")

    val ethInvokeData = inputKey[immutable.Seq[Byte]]("Reveals the data portion that would be sent in a message invoking a function and its arguments on a deployed smart contract")

    val ethKeystoreList = taskKey[immutable.Map[EthAddress,immutable.Set[String]]]("Lists all addresses in known and available keystores, with any aliases that may have been defined")

    val xethLoadCompilationsOmitDups = taskKey[immutable.Map[String,jsonrpc20.Compilation.Contract]]("Loads compiled solidity contracts, omitting contracts with multiple nonidentical contracts of the same name")

    val xethLoadCompilationsKeepDups = taskKey[immutable.Iterable[(String,jsonrpc20.Compilation.Contract)]]("Loads compiled solidity contracts, permitting multiple nonidentical contracts of the same name")

    val xethLoadWalletV3 = taskKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3 for ethAddress")

    val xethLoadWalletV3For = inputKey[Option[wallet.V3]]("Loads a V3 wallet from ethWalletsV3")

    val ethMemorizeAbi = taskKey[Unit]("Prompts for an ABI definition for a contract and inserts it into the sbt-ethereum database")

    val ethKeystoreMemorizeWalletV3 = taskKey[Unit]("Prompts for the JSON of a V3 wallet and inserts it into the sbt-ethereum keystore")

    val ethNextNonce = taskKey[BigInt]("Finds the next nonce for the address defined by setting 'ethAddress'")

    val xethFindCacheFunctionInputsAbiParsers = taskKey[Tuple2[String,Option[immutable.SortedMap[String,EthAddress]]]]("Internal use only -- loads information required by some parsers")

    val ethKeystoreRevealPrivateKey = inputKey[Unit]("Danger! Warning! Unlocks a wallet with a passphrase and prints the plaintext private key directly to the console (standard out)")

    val ethSolidityCompile = taskKey[Unit]("Compiles solidity files")

    val ethQueryRepositoryDatabase = inputKey[Unit]("Primarily for debugging. Query the internal repository database.")

    val xethTriggerDirtyAliasCache = taskKey[Unit]("Indirectly provokes an update of the cache of aliases used for tab completions.")

    val ethSelfPing = taskKey[Option[ClientTransactionReceipt]]("Sends 0 ether from ethAddress to itself")

    val ethSendEther = inputKey[Option[ClientTransactionReceipt]]("Sends ether from ethAddress to a specified account, format 'ethSendEther <to-address-as-hex> <amount> <wei|szabo|finney|ether>'")

    val ethKeystoreInspectWalletV3 = inputKey[Unit]("Prints V3 wallet as JSON to the console.")

    val xethUpdateContractDatabase = taskKey[Boolean]("Integrates newly compiled contracts and stubs (defined in ethKnownStubAddresses) into the contract database. Returns true if changes were made.")

    val ethKeystoreValidateWalletV3 = inputKey[Unit]("Verifies that a V3 wallet can be decoded for an address, and decodes to the expected address.")

    // anonymous tasks

    val warnOnZeroAddress = Def.task {
      val current = ethAddress.value

      if ( current == ZeroEthAddress ) {
        throw new Exception(s"""No valid EthAddress set. Please use 'set ethAddress := "<your ethereum address>"'""")
      }

      true
    }

    val xethUpdateRepositoryDatabase = inputKey[Unit]("Primarily for development and debugging. Update the internal repository database with arbitrary SQL.")

    val findCachePrivateKey = Def.task {
      val checked = warnOnZeroAddress.value

      val CurAddrStr = ethAddress.value
      val CurAddress = EthAddress(CurAddrStr)
      val log = streams.value.log
      val is = interactionService.value
      val mbWallet = xethLoadWalletV3.value

      def updateCached : EthPrivateKey = {
        // this is ugly and awkward, but it gives time for any log messages to get emitted before prompting for a credential
        // it also slows down automated attempts to guess passwords, i guess...
        Thread.sleep(1000)

        val credential = readCredential( is, CurAddress )

        val privateKey = findPrivateKey( log, mbWallet, credential )
        UnlockedKey.set( Some( (CurAddress, privateKey) ) )
        privateKey
      }
      def goodCached : Option[EthPrivateKey] = {
        UnlockedKey.get match {
          case Some( ( CurAddress, privateKey ) ) => Some( privateKey )
          case _                                  => None
        }
      }

      goodCached.getOrElse( updateCached )
    }

    // definitions

    /*
     * The strategy we are using to support dynamic, post-task tab completions
     * is taken most closely from here
     * 
     *    https://github.com/etsy/sbt-compile-quick-plugin/blob/7c99b0197634c72924791591e3a790bd7e0e3e82/src/main/scala/com/etsy/sbt/CompileQuick.scala
     * 
     * See also Josh Suereth here
     * 
     *    http://grokbase.com/t/gg/simple-build-tool/151vq0w03t/sbt-sbt-plugin-developing-using-value-from-anither-task-in-parser-exampels-for-tab-completion
     * 
     * It is still rather mysterious to me.
     */ 
    lazy val ethDefaults : Seq[sbt.Def.Setting[_]] = Seq(

      // Settings

      ethBlockchainId   := MainnetIdentifier,

      ethJsonRpcUrl     := "http://localhost:8545",

      ethEntropySource := new java.security.SecureRandom,

      ethIncludeLocations := Nil,

      ethGasMarkup := 0.2,

      ethGasOverrides := Map.empty[String,BigInt],

      ethGasPriceMarkup := 0.0, // by default, use conventional gas price

      ethKeystoresV3 := {
        def warning( location : String ) : String = s"Failed to find V3 keystore in ${location}"
        def listify( fd : Failable[File] ) = fd.fold( _ => Nil, f => List(f) )
        listify( Repository.KeyStore.V3.Directory.xwarn( warning("sbt-ethereum repository") ) ) ::: listify( clients.geth.KeyStore.Directory.xwarn( warning("geth home directory") ) ) ::: Nil
      },

      ethKnownStubAddresses := Map.empty,

      ethAddress := {
        val mbProperty = Option( System.getProperty( EthAddressSystemProperty ) )
        val mbEnvVar   = Option( System.getenv( EthAddressEnvironmentVariable ) )


        (mbProperty orElse mbEnvVar).getOrElse( ZeroEthAddress )
      },

      ethTargetDir in Compile := (target in Compile).value / "ethereum",

      ethSoliditySource in Compile := (sourceDirectory in Compile).value / "solidity",

      ethSolidityDestination in Compile := (ethTargetDir in Compile).value / "solidity",

      ethWalletV3ScryptN := wallet.V3.Default.Scrypt.N,

      ethWalletV3ScryptR := wallet.V3.Default.Scrypt.R,

      ethWalletV3ScryptP := wallet.V3.Default.Scrypt.P,

      ethWalletV3ScryptDkLen := wallet.V3.Default.Scrypt.DkLen,

      ethWalletV3Pbkdf2C := wallet.V3.Default.Pbkdf2.C,

      ethWalletV3Pbkdf2DkLen := wallet.V3.Default.Pbkdf2.DkLen,

      // tasks

      compile in Compile := {
        val dummy = (ethSolidityCompile in Compile).value
        (compile in Compile).value
      },

      xethLoadAbiFor <<= xethLoadAbiForTask,

      ethAliasDrop <<= ethAliasDropTask,

      ethAliasList := {
        val log = streams.value.log
        val faliases = Repository.Database.findAllAliases
        faliases.fold(
          _ => log.warn("Could not read aliases from repository database."),
          aliases => aliases.foreach { case (alias, address) => println( s"${alias} -> 0x${address.hex}" ) }
        )
      },

      ethAliasSet <<= ethAliasSetTask,

      ethBalance := {
        val checked = warnOnZeroAddress.value
        val s = state.value
        val addressStr = ethAddress.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethBalanceFor, addressStr, s)
        result
      },

      ethBalanceFor <<= ethBalanceForTask,

      ethBalanceInWei := {
        val checked = warnOnZeroAddress.value
        val s = state.value
        val addressStr = ethAddress.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethBalanceInWeiFor, addressStr, s)
        result
      },

      ethBalanceInWeiFor <<= ethBalanceInWeiForTask,

      ethCallConstant <<= ethCallConstantTask,

      ethCompilationsCull := {
        val log = streams.value.log
        val fcount = Repository.Database.cullUndeployedCompilations()
        val count = fcount.get
        log.info( s"Removed $count undeployed compilations from the repository database." )
      },

      ethCompilationsInspect <<= ethCompilationsInspectTask,

      ethCompilationsList := {
        val contractsSummary = Repository.Database.contractsSummary.get // throw for any db problem

        val Blockchain = "Blockchain"
        val Address    = "Contract Address"
        val Name       = "Name"
        val CodeHash   = "Code Hash"
        val Timestamp  = "Deployment Timestamp"

        val cap = "+" + span(12) + "+" + span(44) + "+" + span(22) + "+" + span(68) + "+" + span(30) + "+"
        println( cap )
        println( f"| $Blockchain%-10s | $Address%-42s | $Name%-20s | $CodeHash%-66s | $Timestamp%-28s |" )
        println( cap )

        contractsSummary.foreach { row =>
          import row._
          val id = blankNull( blockchain_id )
          val ca = emptyOrHex( contract_address )
          val nm = blankNull( name )
          val ch = emptyOrHex( code_hash )
          val ts = blankNull( timestamp )
          println( f"| $id%-10s | $ca%-42s | $nm%-20s | $ch%-66s | $ts%-28s |" )
        }
        println( cap )
      },

      ethSolidityCompile in Compile := {
        val log        = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value

        val includeStrings = ethIncludeLocations.value    

        val solSource      = (ethSoliditySource in Compile).value
        val solDestination = (ethSolidityDestination in Compile).value

        val baseDir = baseDirectory.value

        val includeLocations = includeStrings.map( SourceFile.Location.apply( baseDir, _ ) )

        doCompileSolidity( log, jsonRpcUrl, includeLocations, solSource, solDestination )
      },

      ethDefaultGasPrice := {
        val log        = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        doGetDefaultGasPrice( log, jsonRpcUrl )
      },

      xethFindCacheAliasesIfAvailable <<= xethFindCacheAliasesIfAvailableTask.storeAs( xethFindCacheAliasesIfAvailable ).triggeredBy( xethTriggerDirtyAliasCache ),

      xethFindCacheOmitDupsCurrentCompilations <<= xethFindCacheOmitDupsCurrentCompilationsTask storeAs xethFindCacheOmitDupsCurrentCompilations triggeredBy (ethSolidityCompile in Compile),

      xethFindCacheFunctionInputsAbiParsers <<= xethFindCacheFunctionInputsAbiParsersTask.storeAs( xethFindCacheFunctionInputsAbiParsers ).triggeredBy( xethTriggerDirtyAliasCache ),

      ethGasPrice := {
        val log        = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value

        val markup          = ethGasPriceMarkup.value
        val defaultGasPrice = ethDefaultGasPrice.value

        rounded( BigDecimal(defaultGasPrice) * BigDecimal(1 + markup) ).toBigInt 
      },

      ethGenKeyPair := {
        val log = streams.value.log
        val out = EthKeyPair( ethEntropySource.value )

        // a ridiculous overabundance of caution
        assert {
          val checkpub = out.pvt.toPublicKey
          checkpub == out.pub && checkpub.toAddress == out.address
        }

        log.info( s"Generated keypair for address '0x${out.address.hex}'" )

        out
      },

      ethKeystoreCreateWalletV3Pbkdf2 := {
        val log   = streams.value.log
        val c     = ethWalletV3Pbkdf2C.value
        val dklen = ethWalletV3Pbkdf2DkLen.value

        val is = interactionService.value
        val keyPair = ethGenKeyPair.value
        val entropySource = ethEntropySource.value

        log.info( s"Generating V3 wallet, alogorithm=pbkdf2, c=${c}, dklen=${dklen}" )
        val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
        val w = wallet.V3.generatePbkdf2( passphrase = passphrase, c = c, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
        Repository.KeyStore.V3.storeWallet( w ).get // asserts success
      },

      ethKeystoreCreateWalletV3Scrypt := {
        val log   = streams.value.log
        val n     = ethWalletV3ScryptN.value
        val r     = ethWalletV3ScryptR.value
        val p     = ethWalletV3ScryptP.value
        val dklen = ethWalletV3ScryptDkLen.value

        val is = interactionService.value
        val keyPair = ethGenKeyPair.value
        val entropySource = ethEntropySource.value

        log.info( s"Generating V3 wallet, alogorithm=scrypt, n=${n}, r=${r}, p=${p}, dklen=${dklen}" )
        val passphrase = readConfirmCredential(log, is, "Enter passphrase for new wallet: ")
        val w = wallet.V3.generateScrypt( passphrase = passphrase, n = n, r = r, p = p, dklen = dklen, privateKey = Some( keyPair.pvt ), random = entropySource )
        Repository.KeyStore.V3.storeWallet( w ).get // asserts success
      },

      ethKeystoreCreateWalletV3 := ethKeystoreCreateWalletV3Scrypt.value,

      ethInvoke <<= ethInvokeTask,

      ethInvokeData <<= ethInvokeDataTask, 

      ethNextNonce := {
        val log            = streams.value.log
        val jsonRpcUrl     = ethJsonRpcUrl.value
        doGetTransactionCount( log, jsonRpcUrl, EthAddress( ethAddress.value ), jsonrpc20.Client.BlockNumber.Pending )
      },

      ethKeystoreList := {
        val keystoresV3 = ethKeystoresV3.value
        val log         = streams.value.log
        val combined = {
          keystoresV3
            .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xwarning( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
            .foldLeft( Map.empty[EthAddress,wallet.V3] )( ( accum, next ) => accum ++ next )
        }

        // TODO: Aliases as values
        val out = combined.map( tup => ( tup._1, immutable.Set.empty[String] ) )
        val cap = "+" + span(44) + "+"
        val KeystoreAddresses = "Keystore Addresses"
        println( cap )
        println( f"| $KeystoreAddresses%-42s |" )
        println( cap )
        immutable.TreeSet( out.keySet.toSeq.map( address => s"0x${address.hex}" ) : _* ).foreach { ka =>
          println( f"| $ka%-42s |" )
        }
        println( cap )
        out
      },

      xethLoadCompilationsKeepDups := {
        val log = streams.value.log

        val dummy = (ethSolidityCompile in Compile).value // ensure compilation has completed

        val dir = (ethSolidityDestination in Compile).value

        def addContracts( vec : immutable.Vector[(String,jsonrpc20.Compilation.Contract)], name : String ) = {
          val next = {
            val file = new File( dir, name )
            try {
              borrow( new BufferedInputStream( new FileInputStream( file ), BufferSize ) )( Json.parse( _ ).as[immutable.Map[String,jsonrpc20.Compilation.Contract]] )
            } catch {
              case e : Exception => {
                log.warn( s"Bad or unparseable solidity compilation: ${file.getPath}. Skipping." )
                log.warn( s"  --> cause: ${e.toString}" )
                Map.empty[String,jsonrpc20.Compilation.Contract]
              }
            }
          }
          vec ++ next
        }

        dir.list.foldLeft( immutable.Vector.empty[(String,jsonrpc20.Compilation.Contract)] )( addContracts )
      },

      xethLoadCompilationsOmitDups := {
        val log = streams.value.log

        val dummy = (ethSolidityCompile in Compile).value // ensure compilation has completed

        val dir = (ethSolidityDestination in Compile).value

        def addBindingKeepShorterSource( addTo : immutable.Map[String,jsonrpc20.Compilation.Contract], binding : (String,jsonrpc20.Compilation.Contract) ) = {
          val ( name, compilation ) = binding
          addTo.get( name ) match {
            case Some( existingCompilation ) => { // this is a duplicate name, we have to think about whether to add and override or keep the old version
              (existingCompilation.info.mbSource, compilation.info.mbSource) match {
                case ( Some( existingSource ), Some( newSource ) ) => {
                  if ( existingSource.length > newSource.length ) addTo + binding else addTo // use the shorter-sourced duplicate
                }
                case ( None, Some( newSource ) )                   => addTo + binding // but prioritize compilations for which source is known
                case ( Some( existingSource ), None )              => addTo
                case ( None, None )                                => addTo
              }
            }
            case None => addTo + binding // not a duplicate name, so just add the binding
          }
        }
        def addAllKeepShorterSource( addTo : immutable.Map[String,jsonrpc20.Compilation.Contract], nextBindings : Iterable[(String,jsonrpc20.Compilation.Contract)] ) = {
          nextBindings.foldLeft( addTo )( ( accum, next ) => addBindingKeepShorterSource( accum, next ) )
        }
        def addContracts( tup : ( immutable.Map[String,jsonrpc20.Compilation.Contract], immutable.Set[String] ), name : String ) = {
          val ( addTo, overlaps ) = tup
          val next = {
            val file = new File( dir, name )
            try {
              borrow( new BufferedInputStream( new FileInputStream( file ), BufferSize ) )( Json.parse( _ ).as[immutable.Map[String,jsonrpc20.Compilation.Contract]] )
            } catch {
              case e : Exception => {
                log.warn( s"Bad or unparseable solidity compilation: ${file.getPath}. Skipping." )
                log.warn( s"  --> cause: ${e.toString}" )
                Map.empty[String,jsonrpc20.Compilation.Contract]
              }
            }
          }
          val rawNewOverlaps = next.keySet.intersect( addTo.keySet )
          val realNewOverlaps = rawNewOverlaps.foldLeft( immutable.Set.empty[String] ){ ( cur, key ) =>
            val origCodeBcas = BaseCodeAndSuffix( addTo( key ).code )
            val nextCodeBcas = BaseCodeAndSuffix( next( key ).code )

            if ( origCodeBcas.baseCodeHex != nextCodeBcas.baseCodeHex ) cur + key else cur
          }
          ( addAllKeepShorterSource( addTo, next ), overlaps ++ realNewOverlaps )
        }

        val ( rawCompilations, duplicates ) = dir.list.foldLeft( ( immutable.Map.empty[String,jsonrpc20.Compilation.Contract], immutable.Set.empty[String] ) )( addContracts )
        if ( !duplicates.isEmpty ) {
          val dupsStr = duplicates.mkString(", ")
          log.warn( s"The project contains mutiple contracts and/or libraries that have identical names but compile to distinct code: $dupsStr" )
          if ( duplicates.size > 1 ) {
            log.warn( s"Units '$dupsStr' have been dropped from the deployable compilations list as references would be ambiguous." )
          } else {
            log.warn( s"Unit '$dupsStr' has been dropped from the deployable compilations list as references would be ambiguous." )
          }
          rawCompilations -- duplicates
        } else {
          rawCompilations
        }
      },

      xethLoadWalletV3For <<= xethLoadWalletV3ForTask,

      xethLoadWalletV3 := {
        val checked = warnOnZeroAddress.value
        val s = state.value
        val addressStr = ethAddress.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(xethLoadWalletV3For, addressStr, s)
        result
      },

      ethMemorizeAbi := {
        val blockchainId = ethBlockchainId.value
        val jsonRpcUrl = ethJsonRpcUrl.value
        val log = streams.value.log
        val is = interactionService.value
        val ( address, abi ) = readAddressAndAbi( log, is )
        val code = doCodeForAddress( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest )

        val check = Repository.Database.setContractAbi( code, Json.stringify( Json.toJson( abi ) ) ).get // thrown an Exception if there's a database issue
        if (!check) {
          log.info( s"The contract code at address '$address' was already associated with an ABI, which has not been overwritten." )
          log.info( s"Associating address with the known ABI.")
        }
        Repository.Database.insertExistingDeployment( blockchainId, address, code.hex ).get // throw an Exception if there's a database issue

        log.info( s"ABI is now known for the contract at address ${address.hex}" )
      },

      ethKeystoreMemorizeWalletV3 := {
        val log = streams.value.log
        val is = interactionService.value
        val w = readV3Wallet( is )
        val address = w.address // a very cursory check of the wallet, NOT full validation
        Repository.KeyStore.V3.storeWallet( w ).get // asserts success
        log.info( s"Imported JSON wallet for address '0x${address.hex}', but have not validated it.")
        log.info( s"Consider validating the JSON using 'ethKeystoreValidateWalletV3 0x${address.hex}." )
      },

      ethDeployOnly <<= ethDeployOnlyTask,

      ethQueryRepositoryDatabase := {
        val log   = streams.value.log
        val query = DbQueryParser.parsed

        // removed guard of query (restriction to select),
        // since updating SQL issued via executeQuery(...)
        // usefully fails in h3

        val checkDataSource = {
          Repository.Database.DataSource.map { ds =>
            borrow( ds.getConnection() ) { conn =>
              borrow( conn.createStatement() ) { stmt =>
                borrow( stmt.executeQuery( query ) ) { rs =>
                  val rsmd = rs.getMetaData
                  val numCols = rsmd.getColumnCount()
                  val colRange = (1 to numCols)
                  val displaySizes = colRange.map( rsmd.getColumnDisplaySize )
                  val labels = colRange.map( rsmd.getColumnLabel )

                  // XXX: make this pretty. someday.
                  log.info( labels.mkString(", ") )
                  while ( rs.next ) {
                    log.info( colRange.map( rs.getString ).mkString(", ") )
                  }
                }
              }
            }
          }
        }
        if ( checkDataSource.isFailed ) {
          log.warn("Failed to find DataSource!")
          log.warn( checkDataSource.fail.toString )
        }
      },

      ethKeystoreRevealPrivateKey <<= ethKeystoreRevealPrivateKeyTask,

      ethSelfPing := {
        val checked  = warnOnZeroAddress.value
        val address  = ethAddress.value
        val sendArgs = s" ${address} 0 wei"
        val log = streams.value.log

        val s = state.value
	val extract = Project.extract(s)
	val (_, result) = extract.runInputTask(ethSendEther, sendArgs, s)

        val out = result
        out.fold( log.warn("Ping failed! Our attempt to send 0 ether from '${address}' to itself may or may not eventually succeed, but we've timed out before hearing back." ) ) { receipt =>
          log.info( s"Ping succeeded! Sent 0 ether from '${address}' to itself in transaction '0x${receipt.transactionHash.hex}'" )
        }
        out
      },

      ethKeystoreInspectWalletV3 := {
        val keystoreDirs = ethKeystoresV3.value
        val w = xethLoadWalletV3For.evaluated.getOrElse( unknownWallet( keystoreDirs ) )
        println( Json.stringify( w.withLowerCaseKeys ) )
      },

      ethSendEther := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val args = ethSendEtherParser.parsed
        val to = args._1
        val amount = args._2
        val nextNonce = ethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val gas = markupEstimateGas( log, jsonRpcUrl, Some( EthAddress( ethAddress.value ) ), Some(to), Nil, jsonrpc20.Client.BlockNumber.Pending, markup )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), to, Unsigned256( amount ), List.empty[Byte] )
        val privateKey = findCachePrivateKey.value
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Sent ${amount} wei to address '0x${to.hex}' in transaction '0x${hash.hex}'." )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      },

      xethTriggerDirtyAliasCache := { // this is intentionally empty, it's execution just triggers a re-caching of aliases
      },

      xethUpdateContractDatabase := {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val compilations           = xethLoadCompilationsKeepDups.value // we want to "know" every contract we've seen, which might include contracts with multiple names
        val stubNameToAddresses    = ethKnownStubAddresses.value.mapValues( stringSet => stringSet.map( EthAddress.apply ) )
        val stubNameToAddressCodes  = {
          stubNameToAddresses.map { case ( name, addresses ) =>
            ( name, immutable.Map( addresses.map( address => ( address, doCodeForAddress( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Pending ).hex ) ).toSeq : _* ) )
          }
        }
        Repository.Database.updateContractDatabase( compilations, stubNameToAddressCodes ).get
      },

      ethKeystoreValidateWalletV3 <<= ethKeystoreValidateWalletV3Task,

      xethUpdateRepositoryDatabase := {
        val log   = streams.value.log
        val query = DbQueryParser.parsed

        val checkDataSource = {
          Repository.Database.DataSource.map { ds =>
            borrow( ds.getConnection() ) { conn =>
              borrow( conn.createStatement() ) { stmt =>
                val rows = stmt.executeUpdate( query )
                log.info( s"Query succeeded: $query" )
                log.info( s"$rows rows affected." )
              }
            }
          }
        }
        if ( checkDataSource.isFailed ) {
          log.warn("Failed to find DataSource!")
          log.warn( checkDataSource.fail.toString )
        }
      },

      onLoad := {
        val origF : State => State = onLoad.value
        val newF  : State => State = ( state : State ) => {
          val lastState = origF( state )
          Project.runTask( xethTriggerDirtyAliasCache, lastState ) match {
            case None => {
              WARNING.log("Huh? Key 'xethTriggerDirtyAliasCache' was undefined in the original state. Ignoring attempt to run that task in onLoad.")
              lastState
            }
            case Some((newState, Inc(inc))) => {
              WARNING.log("Failed to run xethTriggerDirtyAliasCache on initialization: " + Incomplete.show(inc.tpe))
              lastState
            }
            case Some((newState, Value(_))) => newState
          }
        }
        newF
      },

      watchSources ++= {
        val dir = (ethSoliditySource in Compile).value
        val filter = new FilenameFilter {
          def accept( dir : File, name : String ) = goodSolidityFileName( name )
        }
        if ( dir.exists ) {
          dir.list( filter ).map( name => new File( dir, name ) ).toSeq
        } else {
          Nil
        }
      }
    )

    def ethAliasDropTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genAliasParser )

      Def.inputTaskDyn {
        val log = streams.value.log

        // not sure why, but without this xethFindCacheAliasesIfAvailable, which should be triggered by the parser,
        // sometimes fails initialize t0 parser
        val ensureAliases = xethFindCacheAliasesIfAvailable

        val alias = parser.parsed
        val check = Repository.Database.dropAlias( alias ).get // assert success
        if (check) log.info( s"Alias '${alias}' successfully dropped.")
        else log.warn( s"Alias '${alias}' is not defined, and so could not be dropped." )

        Def.taskDyn {
          xethTriggerDirtyAliasCache
        }
      }
    }

    def ethAliasSetTask : Initialize[InputTask[Unit]] = Def.inputTaskDyn {
      val log = streams.value.log
      val ( alias, address ) = NewAliasParser.parsed
      val check = Repository.Database.createUpdateAlias( alias, address )
      check.fold(
        _.vomit,
        _ => {
          log.info( s"Alias '${alias}' now points to address '${address.hex}'." )
        }
      )

      Def.taskDyn {
        xethTriggerDirtyAliasCache
      }
    }

    def xethLoadAbiForTask : Initialize[InputTask[Abi.Definition]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val blockchainId = ethBlockchainId.value
        abiForAddress( blockchainId, parser.parsed )
      }
    }

    def ethBalanceForTask : Initialize[InputTask[BigDecimal]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val address = parser.parsed
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest, Denominations.Ether )
        result.denominated
      }
    }

    def ethBalanceInWeiForTask : Initialize[InputTask[BigInt]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val address = parser.parsed
        val result = doPrintingGetBalance( log, jsonRpcUrl, address, jsonrpc20.Client.BlockNumber.Latest, Denominations.Wei )
        result.wei
      }
    }

    def ethCallConstantTask : Initialize[InputTask[(Abi.Function,immutable.Seq[DecodedReturnValue])]] = {
      val parser = Defaults.loadForParser(xethFindCacheFunctionInputsAbiParsers)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = true ) )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val from = if ( ethAddress.value == ZeroEthAddress ) None else Some( EthAddress( ethAddress.value ) )
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
        if (! function.constant ) {
          log.warn( s"Function '${function.name}' is not marked constant! An ephemeral call may not succeed, and in any case, no changes to the state of the blockchain will be preserved." )
        }
        val amount = mbWei.getOrElse( Zero )
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = markupEstimateGas( log, jsonRpcUrl, from, Some(contractAddress), callData, jsonrpc20.Client.BlockNumber.Pending, markup )
        log.info( s"Gas estimated for function call: ${gas}" )
        val rawResult = doEthCallEphemeral( log, jsonRpcUrl, from, contractAddress, Some(gas), Some( gasPrice ), Some( amount ), Some( callData ), jsonrpc20.Client.BlockNumber.Latest )
        log.info( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
        log.info( s"Raw result of call to function '${function.name}': 0x${rawResult.hex}" )
        val results = decodeReturnValuesForFunction( rawResult, abiFunction ).get // throw an Exception is we can't get results
        results.length match {
          case 0 => {
            assert( abiFunction.outputs.length == 0 )
            log.info( s"The function ${abiFunction.name} yields no result." )
          }
          case n => {
            assert( abiFunction.outputs.length == n )

            if ( n == 1 ) {
              log.info( s"The function '${abiFunction.name}' yields 1 result." )
            } else {
              log.info( s"The function '${abiFunction.name}' yields ${n} results." )
            }

            def formatResult( idx : Int, result : DecodedReturnValue ) : String = {
              val param = result.parameter
              val sb = new StringBuilder(256)
              sb.append( s" + Result ${idx} of type '${param.`type`}'")
              if ( param.name.length > 0 ) {
                sb.append( s", named '${param.name}'," )
              }
              sb.append( s" is ${result.stringRep}" )
              sb.toString
            }

            Stream.from(1).zip(results).foreach { case ( idx, result ) => log.info( formatResult( idx, result ) ) }
          }
        }
        ( abiFunction, results )
      }
    }

    def ethDeployOnlyTask : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(xethFindCacheOmitDupsCurrentCompilations)( genContractNamesConstructorInputsParser )

      Def.inputTask {
        val log = streams.value.log
        val blockchainId = ethBlockchainId.value
        val jsonRpcUrl = ethJsonRpcUrl.value
        val ( contractName, extraData ) = parser.parsed
        val ( compilation, inputsHex ) = {
          extraData match {
            case None => { 
              // at the time of parsing, a compiled contract is not available. we'll force compilation now, but can't accept contructor arguments
              val contractsMap = xethLoadCompilationsOmitDups.value
              val compilation = contractsMap( contractName )
              ( compilation, "" )
            }
            case Some( ( inputs, abi, compilation ) ) => {
              // at the time of parsing, a compiled contract is available, so we've decoded constructor inputs( if any )
              ( compilation, ethabi.constructorCallData( inputs, abi ).get.hex ) // asserts successful encoding of params
            }
          }
        }
        val codeHex = compilation.code
        val dataHex = codeHex ++ inputsHex
        val address = EthAddress( ethAddress.value )
        val nextNonce = ethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val gas = ethGasOverrides.value.getOrElse( contractName, markupEstimateGas( log, jsonRpcUrl, Some(address), None, dataHex.decodeHex.toImmutableSeq, jsonrpc20.Client.BlockNumber.Pending, markup ) )
        val unsigned = EthTransaction.Unsigned.ContractCreation( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), Zero256, dataHex.decodeHex.toImmutableSeq )
        val privateKey = findCachePrivateKey.value
        val updateChangedDb = xethUpdateContractDatabase.value
        val txnHash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"Contract '${contractName}' deployed in transaction '0x${txnHash.hex}'." )
        val out = awaitTransactionReceipt( log, jsonRpcUrl, txnHash, PollSeconds, PollAttempts )
        out.foreach { receipt =>
          receipt.contractAddress.foreach { ca =>
            log.info( s"Contract '${contractName}' has been assigned address '0x${ca.hex}'." )
            val dbCheck = {
              import compilation.info._
              Repository.Database.insertNewDeployment( blockchainId, ca, codeHex, address, txnHash )
            }
            dbCheck.xwarn("Could not insert information about deployed contract into the repository database")
          }
        }
        out
      }
    }

    def ethCompilationsInspectTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genContractAddressOrCodeHashParser )

      Def.inputTask {
        val blockchainId = ethBlockchainId.value

        println()
        val cap =     "-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-="
        val minicap = "------------------------------------------------------------------------"
        println( cap )
        println("                       CONTRACT INFO DUMP")
        println( cap )

        def section( title : String, body : Option[String], hex : Boolean = false ) : Unit = body.foreach { b =>
          println( minicap )
          println( s"${title}:")
          println();
          println( (if ( hex ) "0x" else "") + b )
        }
        def addressSection( title : String, body : Set[ (String,EthAddress) ] ) : Unit = {
          val ordered = immutable.SortedSet.empty[String] ++ body.map { tup =>
            val ( blockchainId, address ) = tup
            s"0x${address.hex} (on blockchain '${blockchainId}')"
          }
          val bodyOpt = if ( ordered.size == 0 ) None else Some( ordered.mkString(", ") )
          section( title, bodyOpt, false )
        }
        def jsonSection[T : play.api.libs.json.Writes]( title : String, body : Option[T] ) : Unit = {
          section( title, body.map( t => Json.stringify( Json.toJson( t ) ) ), false )
        }

        val source = parser.parsed
        source match {
          case Left( address ) => {
            val mbinfo = Repository.Database.deployedContractInfoForAddress( blockchainId, address ).get // throw any db problem
            mbinfo.fold( println( s"Contract with address '$address' not found." ) ) { info =>
              section( s"Contract Address (on blockchain '${info.blockchainId}')", Some( info.contractAddress.hex ), true )
              section( "Deployer Address", info.mbDeployerAddress.map( _.hex ), true )
              section( "Transaction Hash", info.mbTransactionHash.map( _.hex ), true )
              section( "Deployment Timestamp", info.mbDeployedWhen.map( l => (new Date(l)).toString ) )
              section( "Code Hash", Some( info.codeHash.hex ), true )
              section( "Code", Some( info.code ), true )
              section( "Contract Name", info.mbName )
              section( "Contract Source", info.mbSource )
              section( "Contract Language", info.mbLanguage )
              section( "Language Version", info.mbLanguageVersion )
              section( "Compiler Version", info.mbCompilerVersion )
              section( "Compiler Options", info.mbCompilerOptions )
              jsonSection( "ABI Definition", info.mbAbiDefinition )
              jsonSection( "User Documentation", info.mbUserDoc )
              jsonSection( "Developer Documentation", info.mbDeveloperDoc )
              section( "Metadata", info.mbMetadata )
            }
          }
          case Right( hash ) => {
            val mbinfo = Repository.Database.compilationInfoForCodeHash( hash ).get // throw any db problem
            mbinfo.fold( println( s"Contract with code hash '$hash' not found." ) ) { info =>
              section( "Code Hash", Some( hash.hex ), true )
              section( "Code", Some( info.code ), true )
              section( "Contract Name", info.mbName )
              section( "Contract Source", info.mbSource )
              section( "Contract Language", info.mbLanguage )
              section( "Language Version", info.mbLanguageVersion )
              section( "Compiler Version", info.mbCompilerVersion )
              section( "Compiler Options", info.mbCompilerOptions )
              jsonSection( "ABI Definition", info.mbAbiDefinition )
              jsonSection( "User Documentation", info.mbUserDoc )
              jsonSection( "Developer Documentation", info.mbDeveloperDoc )
              section( "Metadata", info.mbMetadata )
              addressSection( "Deployments", Repository.Database.blockchainIdContractAddressesForCodeHash( hash ).get )
            }
          }
        }
        println( cap )
        println()
      }
    }

    def ethInvokeDataTask : Initialize[InputTask[immutable.Seq[Byte]]] = {
      val parser = Defaults.loadForParser(xethFindCacheFunctionInputsAbiParsers)( genAddressFunctionInputsAbiParser( restrictedToConstants = false ) )

      Def.inputTask {
        val ( contractAddress, function, args, abi ) = parser.parsed
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
        val log = streams.value.log
        log.info( s"Call data: ${callData.hex}" )
        callData
      }
    }

    def ethInvokeTask : Initialize[InputTask[Option[ClientTransactionReceipt]]] = {
      val parser = Defaults.loadForParser(xethFindCacheFunctionInputsAbiParsers)( genAddressFunctionInputsAbiMbValueInWeiParser( restrictedToConstants = false ) )

      Def.inputTask {
        val log = streams.value.log
        val jsonRpcUrl = ethJsonRpcUrl.value
        val caller = EthAddress( ethAddress.value )
        val nextNonce = ethNextNonce.value
        val markup = ethGasMarkup.value
        val gasPrice = ethGasPrice.value
        val ( ( contractAddress, function, args, abi ), mbWei ) = parser.parsed
        val amount = mbWei.getOrElse( Zero )
        val privateKey = findCachePrivateKey.value
        val abiFunction = abiFunctionForFunctionNameAndArgs( function.name, args, abi ).get // throw an Exception if we can't get the abi function here
        val callData = callDataForAbiFunction( args, abiFunction ).get // throw an Exception if we can't get the call data
        log.info( s"Outputs of function are ( ${abiFunction.outputs.mkString(", ")} )" )
        log.info( s"Call data for function call: ${callData.hex}" )
        val gas = markupEstimateGas( log, jsonRpcUrl, Some(caller), Some(contractAddress), callData, jsonrpc20.Client.BlockNumber.Pending, markup )
        log.info( s"Gas estimated for function call: ${gas}" )
        val unsigned = EthTransaction.Unsigned.Message( Unsigned256( nextNonce ), Unsigned256( gasPrice ), Unsigned256( gas ), contractAddress, Unsigned256( amount ), callData )
        val hash = doSignSendTransaction( log, jsonRpcUrl, privateKey, unsigned )
        log.info( s"""Called function '${function.name}', with args '${args.mkString(", ")}', sending ${amount} wei to address '0x${contractAddress.hex}' in transaction '0x${hash.hex}'.""" )
        awaitTransactionReceipt( log, jsonRpcUrl, hash, PollSeconds, PollAttempts )
      }
    }

    def xethLoadWalletV3ForTask : Initialize[InputTask[Option[wallet.V3]]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val keystoresV3 = ethKeystoresV3.value
        val log         = streams.value.log

        val address = parser.parsed
        val out = {
          keystoresV3
            .map( dir => Failable( wallet.V3.keyStoreMap(dir) ).xwarning( "Failed to read keystore directory" ).recover( Map.empty[EthAddress,wallet.V3] ).get )
            .foldLeft( None : Option[wallet.V3] ){ ( mb, nextKeystore ) =>
            if ( mb.isEmpty ) nextKeystore.get( address ) else mb
          }
        }
        log.info( out.fold( s"No V3 wallet found for '0x${address.hex}'" )( _ => s"V3 wallet found for '0x${address.hex}'" ) )
        out
      }
    }

    def ethKeystoreRevealPrivateKeyTask : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val is = interactionService.value
        val log = streams.value.log
        
        val address = parser.parsed
        val addressStr = address.hex

        val s = state.value
	val extract = Project.extract(s)
	val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For, addressStr, s)

        val credential = readCredential( is, address )
        val privateKey = findPrivateKey( log, mbWallet, credential )
        val confirmation = {
          is.readLine(s"Are you sure you want to reveal the unencrypted private key on this very insecure console? [Type YES exactly to continue, anything else aborts]: ", mask = false)
            .getOrElse(throw new Exception("Failed to read a confirmation")) // fail if we can't get a credential
        }
        if ( confirmation == "YES" ) {
          println( s"0x${privateKey.bytes.widen.hex}" )
        } else {
          throw new Exception("Not confirmed by user. Aborted.")
        }
      }
    }

    def ethKeystoreValidateWalletV3Task : Initialize[InputTask[Unit]] = {
      val parser = Defaults.loadForParser(xethFindCacheAliasesIfAvailable)( genGenericAddressParser )

      Def.inputTask {
        val log = streams.value.log
        val is = interactionService.value
        val keystoreDirs = ethKeystoresV3.value
        val s = state.value
	val extract = Project.extract(s)
        val inputAddress = parser.parsed
	val (_, mbWallet) = extract.runInputTask(xethLoadWalletV3For, inputAddress.hex, s)
        val w = mbWallet.getOrElse( unknownWallet( keystoreDirs ) )
        val credential = readCredential( is, inputAddress )
        val privateKey = wallet.V3.decodePrivateKey( w, credential )
        val derivedAddress = privateKey.toPublicKey.toAddress
        if ( derivedAddress != inputAddress ) {
          throw new Exception(
            s"The wallet loaded for '0x${inputAddress.hex}' decodes with the credential given, but to a private key associated with a different address, 0x${derivedAddress}! Keystore files may be mislabeled or corrupted."
          )
        }
        log.info( s"A wallet for address '0x${derivedAddress.hex}' is valid and decodable with the credential supplied." )
      }
    }

    /*
     * Things that need to be defined as tasks so that parsers can load them dynamically...
     */ 

    def xethFindCacheOmitDupsCurrentCompilationsTask : Initialize[Task[immutable.Map[String,jsonrpc20.Compilation.Contract]]] = Def.task {
      xethLoadCompilationsOmitDups.value
    }
    
    def xethFindCacheAliasesIfAvailableTask : Initialize[Task[Option[immutable.SortedMap[String,EthAddress]]]] = Def.task {
      Repository.Database.findAllAliases.toOption
    }

    def xethFindCacheFunctionInputsAbiParsersTask : Initialize[Task[Tuple2[String,Option[immutable.SortedMap[String,EthAddress]]]]] = Def.task {
      val blockchainId = ethBlockchainId.value
      val mbAliases    = xethFindCacheAliasesIfAvailable.value
      ( blockchainId, mbAliases )
    }
  }


  import autoImport._

  // very important to ensure the ordering of settings,
  // so that compile actually gets overridden
  override def requires = JvmPlugin && InteractionServicePlugin

  override def trigger = allRequirements

  override val projectSettings = ethDefaults
}
