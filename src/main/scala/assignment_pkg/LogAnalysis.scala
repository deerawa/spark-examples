package assignment_pkg

import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.OutputMode
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession}

import scala.util.matching.Regex

object LogAnalysis {
  def main(args: Array[String]): Unit = {

    if (args.length < 2 )
      throw new IllegalArgumentException("Invalid no. of arguments provided.. Please specify input dir and output dir")

    val input_files_path: String = args(0)
    val output_files_path: String = args(1)

    val spark = SparkSession.builder()
      .appName("logs-analysis")
      .master("local")
      .getOrCreate()

    val sourceSchema = StructType(Array(StructField("message", StringType, nullable = false)))
    val targetSchema = StructType(Array(
      StructField("timestamp", StringType, nullable = false),
      StructField("cpe_id", StringType, nullable = false),
      StructField("fqdn", StringType, nullable = false),
      StructField("action", StringType, nullable = false),
      StructField("error_code", FloatType, nullable = false),
      StructField("message", StringType, nullable = false)
    ))

    val streamDf: DataFrame = spark.readStream
      .schema(sourceSchema)
      .json(input_files_path)

    val regexPattern: Regex = "(.*?/errors)/(.*?)/(.*?)/(\\d+/\\d+)".r
    val encoder = RowEncoder(targetSchema)

    val transformed_df = streamDf.select(split(col("message"), "\\s+").as("log"))
      .filter(col("log")(12).contains("omwssu"))
      .withColumn("timestamp",
        date_format(concat(col("log")(1), lit(" "), col("log")(2)),
          "yyyy-MM-dd'T'HH:mm:ss.SS'Z'"))
      .withColumn("message", col("log")(12))
      .drop("log")
      .map(func = row => {
        val message_url: String = row.getAs[String]("message")
        val message_ts: String = row.getAs[String]("timestamp")

        val allMatches = regexPattern.findAllMatchIn(message_url)

        val (fqdn, cpeId, action, errorCode) = allMatches.map(m =>
          (m.group(1), m.group(2), m.group(3), m.group(4))).toSeq.head

        val errCode: Float = errorCode.replace("/", ".").toFloat

        Row(message_ts, cpeId, fqdn, action, errCode, message_url)

      })(encoder)

    //transformed_df.write.mode(SaveMode.Append).json(output_files_path)

    transformed_df.writeStream
      .outputMode(OutputMode.Append())
      .option("truncate", value = false)
      .format("json")
      .option("path", output_files_path)
      .option("checkpointLocation", "/tmp/checkpoint")
      .start()
      .awaitTermination()
  }

}
