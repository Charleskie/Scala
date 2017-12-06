package Cal_public_transit.Subway

import org.LocationService
import org.apache.spark.SparkFiles
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, SparkSession}

import scala.io.Source

/**
  * Created by WJ on 2017/11/8.
  */
class Cal_subway extends Serializable{
  /**
    * 连接OD
    * @param sparkSession
    * @param input
    * @return
    */
  def mkOD(sparkSession: SparkSession,input:String,timeSF:String,position:String,ruler:String) = {
    Subway_Clean().getOD(sparkSession,input,timeSF,position,ruler).asInstanceOf[RDD[OD]]
  }

  /**
    * 站点转化为所属行政区
    * @param sparkSession
    * @param input
    * @return
    */
  def mkZoneOD(sparkSession:SparkSession,input:String,timeSF:String,position:String,ruler:String):RDD[OD] = {
    val getod = mkOD(sparkSession,input,timeSF,position,ruler)
    getod.map(line=>{
      val zone1 = Locat10(line.o_station)
      val zone2 = Locat10(line.d_station)
      OD(line.card_id,zone1,line.o_time,zone2,line.d_time,line.time_diff)
    })
  }

  /**
    * 每天的客流
    * @param sparkSession
    * @param input
    */
  def everyDayFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,ruler:String):DataFrame = {
    import sparkSession.implicits._
    val od = mkOD(sparkSession,input,timeSF,position,ruler).toDF()
    val date = udf((s:String)=>s.split("T")(0))
    val withDate = od.withColumn("date",date(col("o_time")))
    val flow = withDate.groupBy(col("date")).count().orderBy(col("date"))
    flow
  }

  /**
    * 求日均客流
    * @param sparkSession
    * @param input
    * @return
    */
  def avgFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,Holiday:String,ruler:String):DataFrame = {
    import sparkSession.implicits._
    everyDayFlow(sparkSession,input,timeSF,position,ruler).map(Row=>{
      val isHoliday = TimeUtils().isFestival(Row.getString(Row.fieldIndex("date")),"yyyy-MM-dd",Holiday)
      (isHoliday,Row.getLong(Row.fieldIndex("count")))
    }).toDF("isFestival","count").groupBy("isFestival").mean("count")
  }
  /**
    * 求分时段客流 早高峰、晚高峰、次高峰、平峰
    * @param sparkSession
    * @param input
    * @return
    */
  def avgPeriodFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,Holiday:String,ruler:String):DataFrame = {
    import sparkSession.implicits._
    everyDayFlow(sparkSession,input,timeSF,position,ruler).map(Row=>{
      val isHoliday = TimeUtils().isFestival(Row.getString(Row.fieldIndex("date")),"yyyy-MM-dd",Holiday)
      val period = TimeUtils().timePeriod(Row.getString(Row.fieldIndex("date")),Holiday)
      (isHoliday,period,Row.getLong(Row.fieldIndex("count")))
    }).toDF("isFestival","period","count").groupBy("isFestival","period").mean("count").orderBy(col("isFestival"),col("period"))
  }

  /**
    * 粒度客流
    * @param sparkSession
    * @param input
    * @param size 粒度可选：5min,10min,15min,30min,hour
    * @return
    */
  def sizeFlow(sparkSession: SparkSession,input:String,size:String,timeSF:String,position:String,ruler:String):DataFrame={
    import sparkSession.implicits._
    val od = mkOD(sparkSession,input,timeSF,position,ruler).toDF()
    val sizeTime = od.map(line=>{
      val o_time = line.getString(line.fieldIndex("o_time"))
      new TimeUtils().timeChange(o_time,size)
    }).toDF("sizeTime")
    val flow = sizeTime.groupBy(col("sizeTime")).count().orderBy(col("sizeTime"))
    flow
  }

  /***
    * OD客流，降序排列
    * @param sparkSession
    * @param input
    * @return
    */
  def dayODFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,ruler:String):DataFrame={
    import sparkSession.implicits._
    val od = mkOD(sparkSession,input,timeSF,position,ruler).toDF()
    val date = udf((s:String)=>s.split("T")(0))
    val withDate = od.withColumn("date",date(col("o_time")))
    val odFlow = withDate.groupBy(col("date"),col("o_station"),col("d_station")).count().orderBy(col("date"),col("count").desc)
    odFlow
  }

  /**
    * OD客流取平均，降序排列
    * @param sparkSession
    * @param input
    * @return
    */
  def avgODFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,Holiday:String,ruler:String):DataFrame={
    import sparkSession.implicits._
    dayODFlow(sparkSession,input,timeSF,position,ruler).map(Row=>{
      val isFestival = TimeUtils().isFestival(Row.getString(Row.fieldIndex("date")),"yyyy-MM-dd",Holiday)
      (isFestival,Row.getString(Row.fieldIndex("o_station")),Row.getString(Row.fieldIndex("d_station")),Row.getLong(Row.fieldIndex("count")))
    }).toDF("isFestival","o_station","d_station","count").groupBy(col("isFestival"),col("o_station"),col("d_station")).mean("count").orderBy(col("isFestival"),col("avg(count)").desc)
  }

  /**
    * OD日均分时段客流，降序排列
    * @param sparkSession
    * @param input
    * @return
    */
  def avgPeriodODFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,Holiday:String,ruler:String):DataFrame={
    import sparkSession.implicits._
    dayODFlow(sparkSession,input,timeSF,position,ruler).map(Row=>{
      val isFestival = TimeUtils().isFestival(Row.getString(Row.fieldIndex("date")),"yyyy-MM-dd",Holiday)
      val period = TimeUtils().timePeriod(Row.getString(Row.fieldIndex("date")),Holiday)
      (isFestival,period,Row.getString(Row.fieldIndex("o_station")),Row.getString(Row.fieldIndex("d_station")),Row.getLong(Row.fieldIndex("count")))
    }).toDF("isFestival","period","o_station","d_station","count").groupBy(col("isFestival"),col("period"),col("o_station"),col("d_station")).mean("count").orderBy(col("isFestival"),col("period"),col("avg(count)").desc)
  }

  /**
    *站点进出站量
    * @param sparkSession
    * @param input
    * @return
    */
  def dayStationIOFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String):DataFrame={
    import sparkSession.implicits._
    val data = sparkSession.sparkContext.textFile(input)
    val usefulData = Subway_Clean().GetFiled(data,timeSF,position)
    val station = usefulData.groupBy(x=>x.deal_time.split("T")(0)+","+x.station_id).mapValues(line=>{
      val InFlow = line.count(_.Type.matches("21"))
      val OutFlow = line.count(_.Type.matches("22"))
      (InFlow,OutFlow)
    }).map(x=>(x._1.split(",")(0),x._1.split(",")(1),x._2._1,x._2._2)).toDF("date","station","InFlow","OutFlow").orderBy(col("date"),(col("InFlow")+col("OutFlow")).desc)
    station
  }
  /**
    * 平均站点进出站量,按进出站总量排序，降序
    * @param sparkSession
    * @param input
    * @return
    */
  def avgStationIOFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,Holiday:String):DataFrame={
    import sparkSession.implicits._
    dayStationIOFlow(sparkSession,input,timeSF,position).map(Row=>{
      val isFestival = TimeUtils().isFestival(Row.getString(Row.fieldIndex("date")),"yyyy-MM-dd",Holiday)
      (isFestival,Row.getString(Row.fieldIndex("station")),Row.getLong(Row.fieldIndex("InFlow")),Row.getLong(Row.fieldIndex("OutFlow")))
    }).toDF("isFestival","station","InFlow","OutFlow").groupBy(col("isFestival"),col("station")).agg("InFlow"->"mean","OutFlow"->"mean").toDF("isFestival","station","InFlow","OutFlow")
      .orderBy(col("isFestival"),(col("InFlow")+col("OutFlow")).desc)
  }

  /**
    * 站点进出站量,按时间粒度计算
    * @param sparkSession
    * @param input
    * @param size 粒度可选：5min,10min,15min,30min,hour
    * @return
    */
  def sizeStationIOFlow(sparkSession: SparkSession,input:String,size:String,timeSF:String,position:String):DataFrame={
    import sparkSession.implicits._
    val data = sparkSession.sparkContext.textFile(input)
    val usefulData = Subway_Clean().GetFiled(data,timeSF,position).map(line=>{
      val changeTime = new TimeUtils().timeChange(line.deal_time,size)
      SZT(line.card_id,changeTime,line.station_id,line.Type)
    })
    val station = usefulData.groupBy(x=>x.deal_time+","+x.station_id).mapValues(line=>{
      val InFlow = line.count(_.Type.matches("21"))
      val OutFlow = line.count(_.Type.matches("22"))
      (InFlow,OutFlow)
    }).map(x=>(x._1.split(",")(0),x._1.split(",")(1),x._2._1,x._2._2)).toDF("changedTime","station","InFlow","OutFlow").orderBy(col("station"),col("changedTime"))
    station
  }

  /**
    * 返回站点所属行政区
    * @param id
    * @return
    */
  def Locat10(id:String):String={
    val file = Source.fromFile(SparkFiles.get("file:///C:/Users/Lhh/Documents/地铁_static/subway_zdbm_station"))
    val id_lonlat =scala.collection.mutable.Map[String,String]()
    val name_lonlat = scala.collection.mutable.Map[String,String]()
    var location:String = null
    for (elem <- file.getLines){
      val s = elem.split(",")
      val id = s(0)
      val name = s(1)
      val lon = s(5)
      val lat = s(4)
      id_lonlat.put(id,lon+","+lat)
      name_lonlat.contains(name) match {
        case false => name_lonlat.put(name,lon+","+lat)
        case true =>
      }
    }
    file.close()
    if(id.matches("2.*")){
      val lon = id_lonlat(id).split(",")(0).toDouble
      val lat = id_lonlat(id).split(",")(1).toDouble
      location = LocationService.locate(lon,lat)
    }else{
      val lon = name_lonlat(id).split(",")(0).toDouble
      val lat = name_lonlat(id).split(",")(1).toDouble
      location = LocationService.locate(lon,lat)
    }
    location
  }

  /***
    * 区域转移客流，降序排列
    * @param sparkSession
    * @param input
    * @return
    */
  def zoneDayODFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,ruler:String):DataFrame={
    import sparkSession.implicits._
    val od = mkZoneOD(sparkSession,input,timeSF,position,ruler).toDF()
    val date = udf((s:String)=>s.split("T")(0))
    val withDate = od.withColumn("date",date(col("o_time")))
    val odFlow = withDate.groupBy(col("date"),col("o_station"),col("d_station")).count().orderBy(col("date"),col("count").desc)
    odFlow.filter("o_station <> '-1' and d_station <> '-1'")
  }

  /**
    * 区域转移客流取平均，降序排列
    * @param sparkSession
    * @param input
    * @return
    */
  def zoneAvgODFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,Holiday:String,ruler:String):DataFrame={
    import sparkSession.implicits._
    zoneDayODFlow(sparkSession,input,timeSF,position,ruler).map(Row=>{
      val isFestival = TimeUtils().isFestival(Row.getString(Row.fieldIndex("date")),"yyyy-MM-dd",Holiday)
      (isFestival,Row.getString(Row.fieldIndex("o_station")),Row.getString(Row.fieldIndex("d_station")),Row.getLong(Row.fieldIndex("count")))
    }).toDF("isFestival","o_station","d_station","count").groupBy(col("isFestival"),col("o_station"),col("d_station")).mean("count").orderBy(col("isFestival"),col("avg(count)").desc).filter("o_station <> '-1' and d_station <> '-1'")
  }

  /**
    *区域聚散客流，按进出站总量排序，降序
    * @param sparkSession
    * @param input
    * @return
    */
  def zoneDayStationIOFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String):DataFrame={
    import sparkSession.implicits._
    val data = sparkSession.sparkContext.textFile(input)
    val usefulData = Subway_Clean().GetFiled(data,timeSF,position).map(x=>SZT(x.card_id,x.deal_time,Locat10(x.station_id),x.Type))
    val station = usefulData.groupBy(x=>x.deal_time.split("T")(0)+","+x.station_id).mapValues(line=>{
      val InFlow = line.count(_.Type.matches("21"))
      val OutFlow = line.count(_.Type.matches("22"))
      (InFlow,OutFlow)
    }).map(x=>(x._1.split(",")(0),x._1.split(",")(1),x._2._1,x._2._2)).toDF("date","station","InFlow","OutFlow").orderBy(col("date"),(col("InFlow")+col("OutFlow")).desc).filter("station <> '-1'")
    station
  }
  /**
    * 平均区域聚散客流,按进出站总量排序，降序
    * @param sparkSession
    * @param input
    * @return
    */
  def zoneAvgStationIOFlow(sparkSession: SparkSession,input:String,timeSF:String,position:String,Holiday:String):DataFrame={
    import sparkSession.implicits._
    zoneDayStationIOFlow(sparkSession,input,timeSF,position).map(Row=>{
      val isFestival = TimeUtils().isFestival(Row.getString(Row.fieldIndex("date")),"yyyy-MM-dd",Holiday)
      (isFestival,Row.getString(Row.fieldIndex("station")),Row.getLong(Row.fieldIndex("InFlow")),Row.getLong(Row.fieldIndex("OutFlow")))
    }).toDF("isFestival","station","InFlow","OutFlow").groupBy(col("isFestival"),col("station")).agg("InFlow"->"mean","OutFlow"->"mean").toDF("isFestival","station","InFlow","OutFlow")
      .orderBy(col("isFestival"),(col("InFlow")+col("OutFlow")).desc).filter("station <> '-1'")
  }
}

object Cal_subway{
  def apply() : Cal_subway = new Cal_subway()

  def main(args: Array[String]): Unit = {
    val sparkSession = SparkSession.builder().config("spark.sql.warehouse.dir", "F:/Github/IhaveADream/spark-warehouse")/*.config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")*/.master("local[2]").getOrCreate()
    val sc = sparkSession.sparkContext
   val input = "G:\\数据\\深圳通地铁\\20170215"
    var lonlatPath = "file:///C:/Users/Lhh/Documents/地铁_static/subway_zdbm_station"
    sc.addFile(lonlatPath)
  sc.textFile(SparkFiles.get(lonlatPath)).foreach(println)
  }
}
