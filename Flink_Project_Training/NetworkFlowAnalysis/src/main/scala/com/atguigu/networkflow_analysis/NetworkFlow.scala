package com.atguigu.networkflow_analysis


import java.sql.Timestamp
import java.text.SimpleDateFormat

import org.apache.flink.api.common.functions.AggregateFunction
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.api.java.tuple.Tuple
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.scala.function.WindowFunction
import org.apache.flink.streaming.api.scala._

import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector

import scala.collection.mutable.ListBuffer


//设置样例类ApacheLogEvent,输入日志数据流
case class ApacheLogEvent(ip: String, userId: String, eventTime: Long, method: String, url: String)

//设置输出样例类，
case class UrlViewCount(url: String, windowEnd: Long, count: Long)


object NetworkFlow {
  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setParallelism(1)
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)

    val dataStream = env
      .readTextFile("F:\\Flink\\Flink_Project_Training\\NetworkFlowAnalysis\\src\\main\\resources\\apache.log")
      .map(line => {
        val lineArray = line.split(" ")
        val simpleDateFromat = new SimpleDateFormat("dd/MM/yyyy:HH:mm:ss")
        val timestamp = simpleDateFromat.parse(lineArray(3)).getTime
        ApacheLogEvent(lineArray(0), lineArray(2), timestamp, lineArray(5), lineArray(6))
      })
      .assignTimestampsAndWatermarks(new BoundedOutOfOrdernessTimestampExtractor[ApacheLogEvent](Time.milliseconds(1000)) {
        override def extractTimestamp(element: ApacheLogEvent): Long = {
          element.eventTime
        }
      })
      .filter(data => {
        val pattern = "^((?!\\.(css|js)$).)*$".r
        (pattern.findFirstIn(data.url)).nonEmpty
      })
      .keyBy(_.url)
      .timeWindow(Time.minutes(10), Time.seconds(5))
      .aggregate(new CountAgg(), new WindowResultFunction())
      .keyBy(1)
      .process(new TopHotUrls(5))
      .print()
    env.execute()

  }
}

class CountAgg() extends AggregateFunction[ApacheLogEvent, Long, Long] {
  override def createAccumulator(): Long = 0L

  override def add(value: ApacheLogEvent, accumulator: Long): Long = accumulator + 1

  override def getResult(accumulator: Long): Long = accumulator

  override def merge(a: Long, b: Long): Long = a + b
}

class WindowResultFunction() extends WindowFunction[Long, UrlViewCount, String, TimeWindow] {
  override def apply(key: String, window: TimeWindow, input: Iterable[Long], out: Collector[UrlViewCount]): Unit = {
    val url: String = key
    val count = input.iterator.next()
    out.collect(UrlViewCount(url, window.getEnd, count))
  }
}

class TopHotUrls(topsize: Int) extends KeyedProcessFunction[Tuple, UrlViewCount, String] {

  private var urlState: ListState[UrlViewCount] = _

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    val urlStateDesc = new ListStateDescriptor[UrlViewCount]("urlState-state", classOf[UrlViewCount])
    urlState = getRuntimeContext.getListState(urlStateDesc)

  }

  override def processElement(value: UrlViewCount, ctx: KeyedProcessFunction[Tuple, UrlViewCount, String]#Context, out: Collector[String]): Unit = {
    urlState.add(value)
    ctx.timerService().registerEventTimeTimer(value.windowEnd + 1)
  }

  override def onTimer(timestamp: Long, ctx: KeyedProcessFunction[Tuple, UrlViewCount, String]#OnTimerContext, out: Collector[String]): Unit = {
    val allUrlViews: ListBuffer[UrlViewCount] = ListBuffer()
    import scala.collection.JavaConversions._
    for (urlView <- urlState.get()) {
      allUrlViews += urlView
    }
    urlState.clear()
    val sortedUrlViews = allUrlViews.sortBy(_.count)(Ordering.Long.reverse).take(topsize)
    val result: StringBuilder = new StringBuilder
    result.append("=================================\n")
    result.append("时间：").append(new Timestamp(timestamp - 1)).append("\n")
    for (i <- sortedUrlViews.indices) {
      val currentUrlView: UrlViewCount = sortedUrlViews(i)
      result.append("NO").append(i + 1).append(":")
        .append(" URL=").append(currentUrlView.url)
        .append(" 流量=").append(currentUrlView.count).append("\n")

    }
    result.append("=============================\n\n")
    Thread.sleep(1000)
    out.collect(result.toString())

  }
}