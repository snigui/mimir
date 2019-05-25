package mimir.test

import java.io._

import sparsity.statement.Statement
import org.specs2.mutable._

import mimir._
import mimir.parser._
import mimir.sql._
import mimir.backend._
import mimir.metadata._
import mimir.algebra._
import mimir.util._
import mimir.exec._
import mimir.exec.result._
import mimir.optimizer._
import mimir.algebra.spark.OperatorTranslation
import mimir.ml.spark.SparkML
import org.specs2.specification.AfterAll

object DBTestInstances
{
  private var databases = scala.collection.mutable.Map[String, Database]()

  def get(tempDBName: String, config: Map[String,String]): Database =
  {
    this.synchronized { 
      databases.get(tempDBName) match { 
        case Some(db) => db
        case None => {
          val dbFile = new File (tempDBName+".db")
          val jdbcBackendMode:String = config.getOrElse("jdbc", "sqlite")
          val shouldResetDB = config.getOrElse("reset", "YES") match { 
            case "NO" => false; case "YES" => true
          }
          val shouldCleanupDB = config.getOrElse("cleanup", config.getOrElse("reset", "YES")) match { 
            case "NO" => false; case "YES" => true
          }
          val shouldEnableInlining = config.getOrElse("inline", "YES") match { 
            case "NO" => false; case "YES" => true
          }
          // println("Preexists: "+dbFile.exists())
          if(shouldResetDB){
            if(dbFile.exists()){ dbFile.delete(); }
          }
          val oldDBExists = dbFile.exists();
          // println("Exists: "+oldDBExists)
          val metadata = new JDBCMetadataBackend(jdbcBackendMode, tempDBName+".db")
          val backend:QueryBackend = 
            config.getOrElse("backend", "spark") match {
              case "spark" => new SparkBackend(tempDBName); 
            }
          val tmpDB = new Database(backend, metadata);
          if(shouldCleanupDB){    
            dbFile.deleteOnExit();
          }
          tmpDB.open()
          backend match {
            case sback:SparkBackend =>{
              val otherExcludeFuncs = Seq("NOT","AND","!","%","&","*","+","-","/","<","<=","<=>","=","==",">",">=","^","|","OR")
                sback.registerSparkFunctions(
                  tmpDB.functions.functionPrototypes.map { _._1 }.toSeq
                    ++ otherExcludeFuncs.map { ID(_) }, 
                  tmpDB
                )
                sback.registerSparkAggregates(
                  tmpDB.aggregates.prototypes.map { _._1 }.toSeq,
                  tmpDB.aggregates
                )
            }
            case _ => ???
          }
          if(shouldResetDB || !oldDBExists){
            config.get("initial_db") match {
              case None => ()
              case Some(path) => Runtime.getRuntime().exec(s"cp $path $dbFile")
            }
          }
          databases.put(tempDBName, tmpDB)
          tmpDB
        }
      }
    }
  }
}

/**
 * Generic superclass for a test.
 * 
 * TODO: Turn this into a trait or set of traits
 */
abstract class SQLTestSpecification(val tempDBName:String, config: Map[String,String] = Map())
  extends Specification
  with SQLParsers
  with RAParsers
  with AfterAll
{
  //sequential
  def afterAll = {
    //TODO: There is likely a better way to do this:
    // hack for spark to delete all cached tables 
    // and temp views that may have shared names between tests
    try{
      if(config.getOrElse("cleanup", config.getOrElse("reset", "YES")) match { 
            case "NO" => false; case "YES" => true
          }) db.backend.dropDB()
      db.backend.close()
      //db.backend.open()
    }catch {
      case t: Throwable => {}
    }
  }
  
  def dbFile = new File(tempDBName+".db")

  def db = DBTestInstances.get(tempDBName, config)

  def select(s: String) = 
    db.sqlToRA(MimirSQL.Select(s))
  def query[T](s: String)(handler: ResultIterator => T): T =
    db.query(select(s))(handler)
  def queryOneColumn[T](s: String)(handler: Iterator[PrimitiveValue] => T): T = 
    query(s){ result => handler(result.map(_(0))) }
  def querySingleton(s: String): PrimitiveValue =
    queryOneColumn(s){ _.next }
  def queryOneRow(s: String): Row =
    query(s){ _.next }
  def table(t: String) =
    db.table(t)
  def resolveViews(q: Operator) =
    db.views.resolve(q)
  def explainRow(s: String, t: String) = 
  {
    val query = resolveViews(select(s))
    db.explainer.explainRow(query, RowIdPrimitive(t))
  }
  def explainCell(s: String, t: String, a:String) = 
  {
    val query = resolveViews(select(s))
    db.explainer.explainCell(query, RowIdPrimitive(t), ID(a))
  }
  def explainEverything(s: String) = 
  {
    val query = resolveViews(select(s))
    db.explainer.explainEverything(query)
  }
  def explainAdaptiveSchema(s: String) =
  {
    val query = resolveViews(select(s))
    db.explainer.explainAdaptiveSchema(query, query.columnNames.toSet, true)
  }  
  def dropTable(t: String) =
    db.update(SQLStatement(sparsity.statement.DropTable(sparsity.Name(t), true)))
  def update(s: MimirStatement) = 
    db.update(s)
  def update(s: String) = 
    db.update(stmt(s))
  def loadCSV(file: String) : Unit =
    db.loadTable(
      sourceFile = file,
      force = true
    )
  def loadCSV(table: String, file: String) : Unit =
    db.loadTable(
      targetTable = Some(ID(table)), 
      sourceFile = file,
      force = true
    )
  def loadCSV(table: String, schema:Seq[(String,String)], file: String) : Unit =
    db.loadTable(
      targetTable = Some(ID(table)), 
      targetSchema = Some(schema.map { x => (ID.upper(x._1), Type.fromString(x._2)) }), 
      sourceFile = file,
      force = true
    ) 
  def loadCSV(table: String, file: String, inferTypes:Boolean, detectHeaders:Boolean) : Unit =
    db.loadTable(
      targetTable = Some(ID(table)), 
      sourceFile = file,
      force = true,
      inferTypes = Some(inferTypes),
      detectHeaders = Some(detectHeaders)
    )
  def loadCSV(table: String, schema:Seq[(String,String)], file: String, inferTypes:Boolean, detectHeaders:Boolean) : Unit =
    db.loadTable(
      targetTable = Some(ID(table)), 
      sourceFile = file,
      targetSchema = Some(schema.map { x => (ID.upper(x._1), Type.fromString(x._2)) }), 
      force = true,
      inferTypes = Some(inferTypes),
      detectHeaders = Some(detectHeaders)
    )
    
  def modelLookup(model: String) = db.models.get(ID(model))
  def schemaLookup(table: String) = db.tableSchema(table).get
 }