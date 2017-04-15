package mimir.adaptive

import scala.collection.mutable

import mimir.Database
import mimir.algebra._
import mimir.statistics.SystemCatalog

class AdaptiveSchemaManager(db: Database)
{
  val dataTable = "MIMIR_ADAPTIVE_SCHEMAS"

  def init(): Unit = 
  {
    if(db.backend.getTableSchema(dataTable).isEmpty){
      db.backend.update(s"""
        CREATE TABLE $dataTable(
          NAME varchar(100), 
          MLENS_TYPE varchar(100),
          QUERY text,
          ARGS text,
          PRIMARY KEY (name)
        )
      """)
    }
  }

  def create(schema: String, mlensType: String, query: Operator, args: Seq[Expression]) = 
  {
    val constructor = MultilensRegistry.multilenses(mlensType)
    val config = MultilensConfig(schema, query, args);
    val models = constructor.initSchema(db, config);
    
    db.backend.update(s"""
      INSERT INTO $dataTable(NAME, MLENS_TYPE, QUERY, ARGS) VALUES (?,?,?,?)
    """, Seq(
      StringPrimitive(schema),
      StringPrimitive(mlensType),
      StringPrimitive(db.querySerializer.serialize(query)),
      StringPrimitive(args.map(db.querySerializer.serialize(_)).mkString("~"))
    ))

    // Persist the associated models
    for(model <- models){
      if(model.isInstanceOf[mimir.models.NeedsReconnectToDatabase])
        model.asInstanceOf[mimir.models.NeedsReconnectToDatabase].reconnectToDatabase(db)
      db.models.persist(model, s"MULTILENS:$schema")
    }
  }

  def all: TraversableOnce[(Multilens, MultilensConfig)] =
  {
    db.query(
      db.getTableOperator(dataTable)
    ).mapRows { row => 
      val name = row(0).asString
      val mlensType = row(1).asString
      val query = db.querySerializer.deserializeQuery(row(2).asString)
      val args:Seq[Expression] = 
        if(row(3).equals(StringPrimitive(""))) { Seq() }
        else { row(3).asString.split("~").map( db.querySerializer.deserializeExpression(_) ) }
 
      ( 
        MultilensRegistry.multilenses(mlensType), 
        MultilensConfig(name, query, args)
      )
    }
  }

  def tableCatalogs: Seq[Operator] =
  {
    all.map { case(mlens, config) => 
      OperatorUtils.projectInColumn(
        "SOURCE",
        StringPrimitive(config.schema),
        OperatorUtils.projectDownToColumns(
          SystemCatalog.tableCatalogSchema.filter(_._1 != "SOURCE").map( _._1 ),
          mlens.tableCatalogFor(db, config)         
        )
      )
    }.toSeq
  }

  def attrCatalogs: Seq[Operator] =
  {
    all.map { case(mlens, config) => 
      OperatorUtils.projectInColumn(
        "SOURCE",
        StringPrimitive(config.schema),
        OperatorUtils.projectDownToColumns(
          SystemCatalog.attrCatalogSchema.filter(_._1 != "SOURCE").map( _._1 ),
          mlens.attrCatalogFor(db, config)         
        )
      )
    }.toSeq
  }

  def get(schema: String): Option[(Multilens, MultilensConfig)] =
  {
    db.query(
      Select(
        Comparison(Cmp.Eq, Var("NAME"), StringPrimitive(schema)),
        db.getTableOperator(dataTable)
      )
    ).mapRows { row => 
      val name = row(0).asString
      val mlensType = row(1).asString
      val query = db.querySerializer.deserializeQuery(row(2).asString)
      val args:Seq[Expression] = 
        if(row(3).equals(StringPrimitive(""))) { Seq() }
        else { row(3).asString.split("~").map( db.querySerializer.deserializeExpression(_) ) }
 
      ( 
        MultilensRegistry.multilenses(mlensType), 
        MultilensConfig(name, query, args)
      )
    }.headOption
  }

  def viewFor(schema: String, table: String): Option[Operator] =
  {
    get(schema).flatMap { case (lens, config) =>
      lens.viewFor(db, config, table)
    }
  }
}