package mimir.util

import org.apache.spark.sql.Row
import mimir.algebra._
import java.sql.SQLException
import java.util.Calendar
import java.util.Date
import java.sql.Timestamp
import org.apache.spark.sql.DataFrame
import mimir.algebra.spark.OperatorTranslation

object SparkUtils {
  def convertFunction(t: Type, field: Integer, dateType: Type = TDate()): (Row => PrimitiveValue) =
  {
    val checkNull: ((Row, => PrimitiveValue) => PrimitiveValue) = {
      (r, call) => {
        val ret = call
        if(r.isNullAt(field)){ NullPrimitive() }
        else { ret }
      }
    }

    t match {
      case TAny() =>        throw new SQLException(s"Can't extract TAny: $field")
      case TFloat() =>      (r) => checkNull(r, { FloatPrimitive(r.getDouble(field)) })
      case TInt() =>        (r) => checkNull(r, { IntPrimitive(r.getLong(field)) })
      case TString() =>     (r) => checkNull(r, { StringPrimitive(r.getString(field)) })
      case TRowId() =>      (r) => checkNull(r, { RowIdPrimitive(r.getString(field)) })
      case TBool() =>       (r) => checkNull(r, { BoolPrimitive(r.getInt(field) != 0) })
      case TType() =>       (r) => checkNull(r, { TypePrimitive(Type.fromString(r.getString(field))) })
      case TDate() =>
        dateType match {
          case TDate() =>   (r) => { val d = r.getDate(field); if(d == null){ NullPrimitive() } else { convertDate(d) } }
          case TString() => (r) => { 
              val d = r.getString(field)
              if(d == null){ NullPrimitive() } 
              else { TextUtils.parseDate(d) }
            }
          case _ =>         throw new SQLException(s"Can't extract TDate as $dateType")
        }
      case TTimestamp() => 
        dateType match {
          case TDate() =>   (r) => { 
              val t = r.getTimestamp(field); 
              if(t == null){ NullPrimitive() } 
              else { convertTimestamp(t) } 
            }
          case TString() => (r) => {
              val t = r.getString(field)
              if(t == null){ NullPrimitive() }
              else { TextUtils.parseTimestamp(t) }
            }
          case _ =>         throw new SQLException(s"Can't extract TTimestamp as $dateType")

        }
      case TInterval() => (r) => { TextUtils.parseInterval(r.getString(field)) }
      case TUser(t) => convertFunction(TypeRegistry.baseType(t), field, dateType)
    }
  }
  
  def convertField(t: Type, results: Row, field: Integer, rowIdType: Type = TString()): PrimitiveValue =
  {
    convertFunction(
      t match {
        case TAny() => OperatorTranslation.getMimirType(results.schema.fields(field).dataType)
        case _ => t
      }, 
      field, 
      rowIdType
    )(results)
  }
  
  def convertDate(c: Calendar): DatePrimitive =
    DatePrimitive(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DATE))
  def convertDate(d: Date): DatePrimitive =
  {
    val cal = Calendar.getInstance();
    cal.setTime(d)
    convertDate(cal)
  }
  def convertDate(d: DatePrimitive): Date =
  {
    val cal = Calendar.getInstance()
    cal.set(d.y, d.m, d.d);
    new Date(cal.getTime().getTime());
  }
  def convertTimestamp(c: Calendar): TimestampPrimitive =
    TimestampPrimitive(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DATE),
                        c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), 
                        c.get(Calendar.MILLISECOND))
  def convertTimestamp(ts: Timestamp): TimestampPrimitive =
  {
    val cal = Calendar.getInstance();
    cal.setTime(ts)
    convertTimestamp(cal)
  }
  def convertTimestamp(ts: TimestampPrimitive): Timestamp =
  {
    val cal = Calendar.getInstance()
    cal.set(ts.y, ts.m, ts.d, ts.hh, ts.mm, ts.ss);
    new Timestamp(cal.getTime().getTime());
  }

  
  def extractAllRows(results: DataFrame): SparkDataFrameIterable =
    extractAllRows(results, OperatorTranslation.structTypeToMimirSchema(results.schema).map(_._2))    
  

  def extractAllRows(results: DataFrame, schema: Seq[Type]): SparkDataFrameIterable =
  {
    new SparkDataFrameIterable(results.rdd.toLocalIterator, schema)
  }
}

class SparkDataFrameIterable(results: Iterator[Row], schema: Seq[Type]) 
  extends Iterator[Seq[PrimitiveValue]]
{
  def next(): List[PrimitiveValue] = 
  {
    val ret = schema.
          zipWithIndex.
          map( t => SparkUtils.convertField(t._1, results.next(), t._2) ).
          toList
    return ret;
  }

  def hasNext(): Boolean = results.hasNext
  def close(): Unit = { }

  def flush: Seq[Seq[PrimitiveValue]] = 
  { 
    val ret = toList
    close()
    return ret
  }
}

