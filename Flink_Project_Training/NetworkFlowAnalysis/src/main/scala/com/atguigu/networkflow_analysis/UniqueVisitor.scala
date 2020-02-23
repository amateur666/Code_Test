package com.atguigu.networkflow_analysis

import com.atguigu.networkflow_analysis.PageView.getClass
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.scala.function.AllWindowFunction
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector
import org.apache.flink.streaming.api.scala._

case class UvCount(windowEnd:Long,count:Long)

object UniqueVisitor {
  def main(args: Array[String]): Unit = {

    val resourcesPath = getClass.getResource("/UserBehavior.csv")
    val env=StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setParallelism(1)

    val dataStream=env.readTextFile(resourcesPath.getPath)
      .map(line=>{
        val lineArray=line.split(",")
        UserBehavior(lineArray(0).trim.toLong,lineArray(1).trim.toLong,lineArray(2).trim.toInt,lineArray(3),lineArray(4).trim.toLong)
      })
      .assignAscendingTimestamps(_.timestamp*1000)
      .filter(_.behavior=="pv")
      .timeWindowAll(Time.seconds(60*60))
      .apply(new UvCountByWindow())
      .print()

    env.execute("Unique Visitor Job")
  }
}

class UvCountByWindow extends AllWindowFunction[UserBehavior,UvCount,TimeWindow]{
  override def apply(window: TimeWindow, input: Iterable[UserBehavior], out: Collector[UvCount]): Unit = {
    val s:collection.mutable.Set[Long]=collection.mutable.Set()
    var idSet=Set[Long]()

    for(userBehavior<-input) {
      idSet+=userBehavior.userId
    }

    out.collect(UvCount(window.getEnd,idSet.size))
  }
}