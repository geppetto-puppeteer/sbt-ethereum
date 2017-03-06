package com.mchange.sc.v1.sbtethereum

import com.mchange.sc.v1.consuela._

import com.mchange.sc.v2.lang.borrow
import com.mchange.sc.v1.consuela.ethereum.{EthAddress,EthHash}
import com.mchange.sc.v1.consuela.ethereum.jsonrpc20._
import com.mchange.sc.v2.sql.{getMaybeSingleString,getMaybeSingleValue,setMaybeString}
import com.mchange.sc.v1.log.MLevel._

import com.mchange.sc.v1.reconcile.{Reconcilable,CantReconcileException}

import java.io.StringReader
import java.sql.{Connection,PreparedStatement,ResultSet,Types,Timestamp}

import javax.sql.DataSource

import scala.collection._

import play.api.libs.json._

object Schema_h2_v0 {

  private implicit lazy val logger = mlogger( this )

  def setClob( ps : PreparedStatement, i : Int, str : String ) = ps.setClob( i, new StringReader( str ) )
  def setClobOption( ps : PreparedStatement, i : Int, mbs : Option[String] ) = mbs.fold( ps.setNull( i, Types.CLOB ) ){ str =>  setClob( ps, i, str ) }
  def setVarcharOption( ps : PreparedStatement, i : Int, mbs : Option[String] ) = mbs.fold( ps.setNull( i, Types.VARCHAR ) ){ str =>  ps.setString( i, str ) }
  def setTimestampOption( ps : PreparedStatement, i : Int, mbl : Option[Long] ) = mbl.fold( ps.setNull( i, Types.TIMESTAMP ) ){ l =>  ps.setTimestamp( i, new Timestamp(l) ) }

  val SchemaVersion = 0

  def ensureSchema( dataSource : DataSource ) = {
    borrow( dataSource.getConnection() ){ conn =>
      borrow( conn.createStatement() ){ stmt =>
        stmt.executeUpdate( Table.Metadata.CreateSql )
        stmt.executeUpdate( Table.KnownCode.CreateSql )
        stmt.executeUpdate( Table.KnownCompilations.CreateSql )
        stmt.executeUpdate( Table.DeployedCompilations.CreateSql )
        stmt.executeUpdate( Table.AddressAliases.CreateSql )
        stmt.executeUpdate( Table.AddressAliases.CreateIndex )
      }
      Table.Metadata.ensureSchemaVersion( conn )
    }
  }

  val ContractsSummarySql = {
    """|SELECT DISTINCT contract_address, name, deployer_address, known_compilations.full_code_hash, txn_hash, deployed_when
       |FROM deployed_compilations RIGHT JOIN known_compilations ON deployed_compilations.full_code_hash = known_compilations.full_code_hash
       |ORDER BY deployed_when ASC""".stripMargin
  }
  val CullUndeployedCompilationsSql = {
    """|DELETE FROM known_compilations 
       |WHERE full_code_hash NOT IN (
       |  SELECT full_code_hash
       |  FROM deployed_compilations
       |)""".stripMargin
  }
  final object Table {
    final object Metadata {
      val CreateSql = "CREATE TABLE IF NOT EXISTS metadata ( key VARCHAR(64) PRIMARY KEY, value VARCHAR(64) NOT NULL )"

      def ensureSchemaVersion( conn : Connection ) : Unit = {
        val currentVersion = select( conn, Key.SchemaVersion )
        currentVersion.fold( insert( conn, Key.SchemaVersion, SchemaVersion.toString ) ){ versionStr =>
          val v = versionStr.toInt
          if ( v != 0 ) throw new DatabaseVersionException( s"Expected version 0, found version ${v}, cannot migrate." )
        }
      }

      def insert( conn : Connection, key : String, value : String ) : Unit = {
        borrow( conn.prepareStatement( "INSERT INTO metadata ( key, value ) VALUES ( ?, ? )" ) ) { ps =>
          ps.setString( 1, key )
          ps.setString( 2, value )
          ps.executeUpdate()
        }
      }

      def select( conn : Connection, key : String ) : Option[String] = {
        borrow( conn.prepareStatement( "SELECT value FROM metadata WHERE key = ?" ) ) { ps =>
          ps.setString(1, key)
          borrow( ps.executeQuery() )( getMaybeSingleString )
        }
      }

      final object Key {
        val SchemaVersion = "SchemaVersion"
      }
    }

    final object KnownCode {
      val CreateSql = {
        """|CREATE TABLE IF NOT EXISTS known_code (
           |   base_code_hash  CHAR(128) PRIMARY KEY,
           |   base_code       CLOB NOT NULL
           |)""".stripMargin
      }
      val SelectSql = "SELECT base_code FROM known_code WHERE base_code_hash = ?"

      val UpsertSql = "MERGE INTO known_code ( base_code_hash, base_code ) VALUES ( ?, ? )"

      def select( conn : Connection, baseCodeHash : EthHash ) : Option[String] = {
        borrow( conn.prepareStatement( SelectSql ) ) { ps =>
          ps.setString(1, baseCodeHash.hex)
          borrow( ps.executeQuery() )( getMaybeSingleString )
        }
      }
      def upsert( conn : Connection, baseCode : String ) : Unit = {
        borrow( conn.prepareStatement( UpsertSql ) ) { ps =>
          ps.setString( 1, EthHash.hash( baseCode.decodeHex ).hex )
          ps.setString( 2, baseCode )
          ps.executeUpdate()
        }
      }
    }

    final object KnownCompilations {
      val CreateSql = {
        """|CREATE TABLE IF NOT EXISTS known_compilations (
           |   full_code_hash    CHAR(128),
           |   base_code_hash    CHAR(128),
           |   code_suffix       CLOB NOT NULL,
           |   name              VARCHAR(128),
           |   source            CLOB,
           |   language          VARCHAR(64),
           |   language_version  VARCHAR(64),
           |   compiler_version  VARCHAR(64),
           |   compiler_options  VARCHAR(256),
           |   abi_definition    CLOB,
           |   user_doc          CLOB,
           |   developer_doc     CLOB,
           |   metadata          CLOB,
           |   PRIMARY KEY ( full_code_hash ),
           |   FOREIGN KEY ( base_code_hash ) REFERENCES known_code ( base_code_hash ) ON DELETE CASCADE
           |)""".stripMargin // we delete known_compilations when culling undeployed compilations
      }
      val SelectSql = {
        """|SELECT
           |   base_code_hash,
           |   code_suffix,
           |   name,
           |   source,
           |   language,
           |   language_version,
           |   compiler_version,
           |   compiler_options,
           |   abi_definition,
           |   user_doc,
           |   developer_doc,
           |   metadata
           |FROM known_compilations
           |WHERE full_code_hash = ?""".stripMargin
      }
      val UpsertSql = {
        """|MERGE INTO known_compilations (
           |   full_code_hash,
           |   base_code_hash,
           |   code_suffix,
           |   name,
           |   source,
           |   language,
           |   language_version,
           |   compiler_version,
           |   compiler_options,
           |   abi_definition,
           |   user_doc,
           |   developer_doc,
           |   metadata
           |) VALUES( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? )""".stripMargin
      }
      val UpdateAbiSql = {
        """|UPDATE known_compilations
           |SET abi_definition = ?
           |WHERE full_code_hash = ?""".stripMargin
      }
      case class KnownCompilation (
        fullCodeHash      : EthHash,
        baseCodeHash      : EthHash,
        codeSuffix        : String,
        mbName            : Option[String],
        mbSource          : Option[String],
        mbLanguage        : Option[String],
        mbLanguageVersion : Option[String],
        mbCompilerVersion : Option[String],
        mbCompilerOptions : Option[String],
        mbAbiDefinition   : Option[Abi.Definition],
        mbUserDoc         : Option[Doc.User],
        mbDeveloperDoc    : Option[Doc.Developer],
        mbMetadata        : Option[String]
      ) extends Reconcilable[KnownCompilation] {
        import Reconcilable._

        def reconcile(other : KnownCompilation) : KnownCompilation = {
          KnownCompilation(
            fullCodeHash      = reconcileLeaf( this.fullCodeHash, other.fullCodeHash ),
            baseCodeHash      = reconcileLeaf( this.baseCodeHash, other.baseCodeHash ),
            codeSuffix        = reconcileLeaf( this.codeSuffix, other.codeSuffix ),
            mbName            = reconcileLeaf( this.mbName, other.mbName ),
            mbSource          = reconcileLeaf( this.mbSource, other.mbSource ),
            mbLanguage        = reconcileLeaf( this.mbLanguage, other.mbLanguage ),
            mbLanguageVersion = reconcileLeaf( this.mbLanguageVersion, other.mbLanguageVersion ),
            mbCompilerVersion = reconcileLeaf( this.mbCompilerVersion, other.mbCompilerVersion ),
            mbCompilerOptions = reconcileLeaf( this.mbCompilerOptions, other.mbCompilerOptions ),
            mbAbiDefinition   = reconcileLeaf( this.mbAbiDefinition, other.mbAbiDefinition ),
            mbUserDoc         = reconcileLeaf( this.mbUserDoc, other.mbUserDoc ),
            mbDeveloperDoc    = reconcileLeaf( this.mbDeveloperDoc, other.mbDeveloperDoc ),
            mbMetadata        = reconcileLeaf( this.mbMetadata, other.mbMetadata )
          )
        }

        def reconcileOver(other : KnownCompilation) : KnownCompilation = {
          KnownCompilation(
            fullCodeHash      = reconcileOverLeaf( this.fullCodeHash, other.fullCodeHash ),
            baseCodeHash      = reconcileOverLeaf( this.baseCodeHash, other.baseCodeHash ),
            codeSuffix        = reconcileOverLeaf( this.codeSuffix, other.codeSuffix ),
            mbName            = reconcileOverLeaf( this.mbName, other.mbName ),
            mbSource          = reconcileOverLeaf( this.mbSource, other.mbSource ),
            mbLanguage        = reconcileOverLeaf( this.mbLanguage, other.mbLanguage ),
            mbLanguageVersion = reconcileOverLeaf( this.mbLanguageVersion, other.mbLanguageVersion ),
            mbCompilerVersion = reconcileOverLeaf( this.mbCompilerVersion, other.mbCompilerVersion ),
            mbCompilerOptions = reconcileOverLeaf( this.mbCompilerOptions, other.mbCompilerOptions ),
            mbAbiDefinition   = reconcileOverLeaf( this.mbAbiDefinition, other.mbAbiDefinition ),
            mbUserDoc         = reconcileOverLeaf( this.mbUserDoc, other.mbUserDoc ),
            mbDeveloperDoc    = reconcileOverLeaf( this.mbDeveloperDoc, other.mbDeveloperDoc ),
            mbMetadata        = reconcileOverLeaf( this.mbMetadata, other.mbMetadata )
          )
        }
      }
      def select( conn : Connection, fullCodeHash : EthHash ) : Option[KnownCompilation] = {
        import Json.parse
        val extract : ResultSet => KnownCompilation = { rs =>
          KnownCompilation (
            fullCodeHash      = fullCodeHash,
            baseCodeHash      = EthHash.withBytes( rs.getString( "base_code_hash" ).decodeHex ),
            codeSuffix        = rs.getString( "code_suffix" ),
            mbName            = Option( rs.getString("name") ),
            mbSource          = Option( rs.getString("source") ),
            mbLanguage        = Option( rs.getString("language") ),
            mbLanguageVersion = Option( rs.getString("language_version") ),
            mbCompilerVersion = Option( rs.getString("compiler_version") ),
            mbCompilerOptions = Option( rs.getString("compiler_options") ),
            mbAbiDefinition   = Option( rs.getString("abi_definition") ).map( parse ).map( _.as[Abi.Definition] ),
            mbUserDoc         = Option( rs.getString("user_doc") ).map( parse ).map( _.as[Doc.User] ),
            mbDeveloperDoc    = Option( rs.getString("developer_doc") ).map( parse ).map( _.as[Doc.Developer] ),
            mbMetadata        = Option( rs.getString("metadata") )
          )
        }
        borrow( conn.prepareStatement( SelectSql ) ) { ps =>
          ps.setString(1, fullCodeHash.hex)
          borrow( ps.executeQuery() )( getMaybeSingleValue( extract ) )
        }
      }
      def upsert(
        conn              : Connection,
        fullCodeHash      : EthHash,
        baseCodeHash      : EthHash,
        codeSuffix        : String,
        mbName            : Option[String],
        mbSource          : Option[String],
        mbLanguage        : Option[String],
        mbLanguageVersion : Option[String],
        mbCompilerVersion : Option[String],
        mbCompilerOptions : Option[String],
        mbAbiDefinition   : Option[Abi.Definition],
        mbUserDoc         : Option[Doc.User],
        mbDeveloperDoc    : Option[Doc.Developer],
        mbMetadata        : Option[String]
      ) : Unit = {
        import Json.{toJson,stringify}
        borrow( conn.prepareStatement( UpsertSql ) ) { ps =>
          ps.setString( 1, fullCodeHash.hex )
          ps.setString( 2, baseCodeHash.hex )
          ps.setString( 3, codeSuffix )
          setMaybeString( Types.VARCHAR )( ps,  4, mbName )
          setMaybeString( Types.CLOB )   ( ps,  5, mbSource )
          setMaybeString( Types.VARCHAR )( ps,  6, mbLanguage )
          setMaybeString( Types.VARCHAR )( ps,  7, mbLanguageVersion )
          setMaybeString( Types.VARCHAR )( ps,  8, mbCompilerVersion )
          setMaybeString( Types.VARCHAR )( ps,  9, mbCompilerOptions )
          setMaybeString( Types.CLOB )   ( ps, 10, mbAbiDefinition.map( ad => stringify( toJson( ad ) ) ) )
          setMaybeString( Types.CLOB )   ( ps, 11, mbUserDoc.map( ud => stringify( toJson( ud ) ) ) )
          setMaybeString( Types.CLOB )   ( ps, 12, mbDeveloperDoc.map( dd => stringify( toJson( dd ) ) ) )
          setMaybeString( Types.CLOB )   ( ps, 13, mbMetadata )
          ps.executeUpdate()
        }
      }
      def upsert(
        conn : Connection,
        kc   : KnownCompilation
      ) : Unit = {
        upsert(
          conn,
          kc.fullCodeHash,
          kc.baseCodeHash,
          kc.codeSuffix,
          kc.mbName,
          kc.mbSource,
          kc.mbLanguage,
          kc.mbLanguageVersion,
          kc.mbCompilerVersion,
          kc.mbCompilerOptions,
          kc.mbAbiDefinition,
          kc.mbUserDoc,
          kc.mbDeveloperDoc,
          kc.mbMetadata
        )
      }
      def updateAbiDefinition( conn : Connection, baseCodeHash : EthHash, fullCodeHash : EthHash, mbAbiDefinition : Option[String] ) : Boolean = {
        borrow( conn.prepareStatement( UpdateAbiSql ) ){ ps =>
          mbAbiDefinition match {
            case Some( abiDefinition ) => ps.setString( 1, abiDefinition )
            case None                  => ps.setNull( 1, Types.CLOB )
          }
          ps.setString( 2, fullCodeHash.hex )
          ps.executeUpdate() match {
            case 0 => false
            case 1 => true
            case n => throw new RepositoryException( s"ABI update should affect no more than one row, affected ${n} rows." )
          }
        }
      }
    }

    final object DeployedCompilations {
      val CreateSql = {
        """|CREATE TABLE IF NOT EXISTS deployed_compilations (
           |   contract_address CHAR(40) PRIMARY KEY,
           |   base_code_hash   CHAR(128) NOT NULL,
           |   full_code_hash   CHAR(128) NOT NULL,
           |   deployer_address CHAR(40),
           |   txn_hash         CHAR(128),
           |   deployed_when    TIMESTAMP,
           |   FOREIGN KEY ( base_code_hash, full_code_hash ) REFERENCES known_compilations( base_code_hash, full_code_hash )
           |)""".stripMargin
      }
      val SelectSql = {
        """|SELECT contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when
           |FROM deployed_compilations
           |WHERE contract_address = ?""".stripMargin
      }
      val InsertSql = {
        "INSERT INTO deployed_compilations ( contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when ) VALUES ( ?, ?, ?, ?, ?, ? )"
      }
      val AllForFullCodeHashSql = {
        """|SELECT contract_address, base_code_hash, full_code_hash, deployer_address, txn_hash, deployed_when
           |FROM deployed_compilations
           |WHERE full_code_hash = ?""".stripMargin
      }
      final case class DeployedCompilation (
        contractAddress : EthAddress,
        baseCodeHash    : EthHash,
        fullCodeHash    : EthHash,
        mbDeployerAddress : Option[EthAddress],
        mbTransactionHash : Option[EthHash],
        mbDeployedWhen    : Option[Long]
      )
      val extract : ResultSet => DeployedCompilation = { rs =>
        DeployedCompilation (
          contractAddress = EthAddress( rs.getString( "contract_address" ) ),
          baseCodeHash    = EthHash.withBytes( rs.getString( "base_code_hash" ).decodeHex ),
          fullCodeHash    = EthHash.withBytes( rs.getString( "full_code_hash" ).decodeHex ),
          mbDeployerAddress = Option( rs.getString( "deployer_address" ) ).map( EthAddress.apply ),
          mbTransactionHash = Option( rs.getString( "txn_hash" ) ).map( _.decodeHex ).map( EthHash.withBytes ),
          mbDeployedWhen    = Option( rs.getTimestamp( "deployed_when" ) ).map( _.getTime )
        )
      }
      def select( conn : Connection, contractAddress : EthAddress ) : Option[DeployedCompilation] = {
        borrow( conn.prepareStatement( SelectSql ) ) { ps =>
          ps.setString(1, contractAddress.hex)
          borrow( ps.executeQuery() )( getMaybeSingleValue( extract ) )
        }
      }
      def allForFullCodeHash( conn : Connection, fullCodeHash : EthHash ) : immutable.Set[DeployedCompilation] = {
        borrow( conn.prepareStatement( AllForFullCodeHashSql ) ) { ps =>
          ps.setString(1, fullCodeHash.hex)
          borrow( ps.executeQuery() ) { rs =>
            var out = immutable.Set.empty[DeployedCompilation]
            while ( rs.next() ) out = out + extract( rs )
            out
          }
        }
      }
      def insertNewDeployment( conn : Connection, contractAddress : EthAddress, code : String, deployerAddress : EthAddress, transactionHash : EthHash ) : Unit = {
        val bcas = BaseCodeAndSuffix( code )
        val timestamp = new Timestamp( System.currentTimeMillis )
        borrow( conn.prepareStatement( InsertSql ) ) { ps =>
          ps.setString   ( 1, contractAddress.hex )
          ps.setString   ( 2, bcas.baseCodeHash.hex )
          ps.setString   ( 3, bcas.fullCodeHash.hex )
          ps.setString   ( 4, deployerAddress.hex )
          ps.setString   ( 5, transactionHash.hex )
          ps.setTimestamp( 6, timestamp )
          ps.executeUpdate()
        }
      }
      def insertExistingDeployment( conn : Connection, contractAddress : EthAddress, code : String ) : Unit = {
        val bcas = BaseCodeAndSuffix( code )
        borrow( conn.prepareStatement( InsertSql ) ) { ps =>
          ps.setString( 1, contractAddress.hex )
          ps.setString( 2, bcas.baseCodeHash.hex )
          ps.setString( 3, bcas.fullCodeHash.hex )
          ps.setNull  ( 4, Types.CHAR )
          ps.setNull  ( 5, Types.CHAR )
          ps.setNull  ( 6, Types.TIMESTAMP )
          ps.executeUpdate()
        }
      }
    }

    final object AddressAliases {
      val CreateSql = {
        """|CREATE TABLE IF NOT EXISTS address_aliases (
           |   alias   VARCHAR(128) PRIMARY KEY,
           |   address CHAR(40) NOT NULL
           |)""".stripMargin
      }
      val CreateIndex = "CREATE INDEX IF NOT EXISTS address_aliases_address_idx ON address_aliases( address )"

      private val InsertSql = {
        """|MERGE INTO address_aliases ( alias, addres )
           |VALUES( ?, ? )""".stripMargin
      }
      def selectByAlias( conn : Connection, alias : String ) : Option[EthAddress] = {
        borrow( conn.prepareStatement( "SELECT address FROM address_aliases WHERE alias = ?" ) ) { ps =>
          ps.setString(1, alias)
          val mbAddressStr = borrow( ps.executeQuery() )( getMaybeSingleString )
          mbAddressStr.map( EthAddress.apply )
        }
      }
      def selectByAddress( conn : Connection, address : EthAddress ) : Option[String] = {
        borrow( conn.prepareStatement( "SELECT alias FROM address_aliases WHERE address = ?" ) ) { ps =>
          ps.setString(1, address.hex)
          borrow( ps.executeQuery() )( getMaybeSingleString )
        }
      }
      def select( conn : Connection ) : immutable.SortedMap[String,EthAddress] = {
        val buffer = mutable.ArrayBuffer.empty[Tuple2[String,EthAddress]]
        borrow( conn.prepareStatement( "SELECT alias, address FROM address_aliases ORDER BY alias ASC" ) ) { ps =>
          borrow( ps.executeQuery() ) { rs =>
            while ( rs.next() ) {
              buffer += Tuple2( rs.getString(1), EthAddress( rs.getString(2) ) )
            }
          }
        }
        immutable.SortedMap( buffer : _* )
      }
      def upsert( conn : Connection, alias : String, address : EthAddress ) : Unit = {
        borrow( conn.prepareStatement( "MERGE INTO address_aliases ( alias, address ) VALUES ( ?, ? )" ) ) { ps =>
          ps.setString( 1, alias )
          ps.setString( 2, address.hex )
          ps.executeUpdate()
        }
      }
      def delete( conn : Connection, alias : String ) : Boolean = {
        borrow( conn.prepareStatement( "DELETE FROM address_aliases WHERE ALIAS = ?" ) ) { ps =>
          ps.setString( 1, alias )
          ps.executeUpdate() == 1
        }
      }
    }
  }
}


