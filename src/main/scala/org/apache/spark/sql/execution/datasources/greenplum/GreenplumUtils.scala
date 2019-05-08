/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.greenplum

import java.io._
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.util.UUID

import org.postgresql.copy.CopyManager
import org.postgresql.core.BaseConnection

import org.apache.spark.SparkEnv
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, Row}
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.datasources.jdbc.JdbcUtils
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

object GreenplumUtils extends Logging {

  def makeConverter(
      dataType: DataType,
      options: GreenplumOptions): (Row, Int) => String = dataType match {
    case StringType => (r: Row, i: Int) => r.getString(i)
    case BooleanType => (r: Row, i: Int) => r.getBoolean(i).toString
    case ByteType => (r: Row, i: Int) => r.getByte(i).toString
    case ShortType => (r: Row, i: Int) => r.getShort(i).toString
    case IntegerType => (r: Row, i: Int) => r.getInt(i).toString
    case LongType => (r: Row, i: Int) => r.getLong(i).toString
    case FloatType => (r: Row, i: Int) => r.getFloat(i).toString
    case DoubleType => (r: Row, i: Int) => r.getDouble(i).toString
    case DecimalType() => (r: Row, i: Int) => r.getDecimal(i).toString

    case DateType =>
      (r: Row, i: Int) => options.dateFormat.format(DateTimeUtils.toJavaDate(r.getInt(i)))

    case TimestampType => (r: Row, i: Int) =>
      options.timestampFormat.format(DateTimeUtils.toJavaTimestamp(r.getLong(i)))

    case BinaryType => (r: Row, i: Int) =>
      new String(r.getAs[Array[Byte]](i), StandardCharsets.UTF_8)

    case udt: UserDefinedType[_] => makeConverter(udt.sqlType, options)
    case _ => (row: Row, ordinal: Int) => row.get(ordinal).toString
  }

  /**
   * https://www.postgresql.org/docs/9.2/sql-copy.html
   *
   * @param df the [[DataFrame]] will be copy to the Greenplum
   * @param schema the table schema in Greemnplum
   * @param options Options for the Greenplum data source
   * @param isAppend whether append data to this gptable
   */
  def copyToGreenplum(
      df: DataFrame,
      schema: StructType,
      options: GreenplumOptions,
      isAppend: Boolean): Unit = {
    val valueConverters: Array[(Row, Int) => String] =
      schema.map(s => makeConverter(s.dataType, options)).toArray

    def convertRow(row: Row): Array[Byte] = {
      var i = 0
      val values = new Array[String](schema.length)
      while (i < schema.length) {
        if (!row.isNullAt(i)) {
          values(i) = convertValue(valueConverters(i).apply(row, i))
        } else {
          values(i) = "NULL"
        }
        i += 1
      }
      (values.mkString(options.delimiter) + "\n").getBytes("UTF-8")
    }

    def convertValue(str: String): String = {
      assert(options.delimiter.length == 1, "The delimiter should be a single character.")
      val delimiter = options.delimiter.charAt(0)
      str.flatMap {
        case '\\' => "\\\\"
        case '\n' => "\\n"
        case '\r' => "\\r"
        case `delimiter` => s"\\$delimiter"
        case c => s"$c"
      }
    }

    var finalDf = df
    if (isAppend) {
      // TODO: For the case, which should append data to table, is there more efficient method?
      finalDf = df.coalesce(1)
    }

    val partNum = finalDf.rdd.getNumPartitions
    val accumulator = finalDf.sparkSession.sparkContext.longAccumulator("copySuccess")
    val randomString = UUID.randomUUID().toString.flatMap {
      case '-' => ""
      case c => s"$c"
    }

    var tempTable = options.table + randomString
    if (isAppend) {
      tempTable = options.table
    }
    val strSchema = JdbcUtils.schemaString(finalDf, options.url, options.createTableColumnTypes)
    finalDf.foreachPartition { rows =>
      val conn = JdbcUtils.createConnectionFactory(options)()
      val copyManager = new CopyManager(conn.asInstanceOf[BaseConnection])
      var committed = false
      try {
        val tmpDir = Utils.createTempDir(Utils.getLocalDir(SparkEnv.get.conf), "greenplum")
        val dataFile = new File(tmpDir, UUID.randomUUID().toString)
        val out = new BufferedOutputStream(new FileOutputStream(dataFile))
        try {
          rows.foreach(r => out.write(convertRow(r)))
        } finally {
          out.close()
        }
        val in = new BufferedInputStream(new FileInputStream(dataFile))

        val createTblSql = s"CREATE TABLE IF NOT EXISTS $tempTable ($strSchema)" +
          s" ${options.createTableOptions}"
        executeStatement(conn, createTblSql)

        val sql = s"COPY $tempTable" +
          s" FROM STDIN WITH NULL AS 'NULL' DELIMITER AS E'${options.delimiter}'"
        try {
          logInfo("Start copy steam to Greenplum")
          val start = System.nanoTime()
          val nums = copyManager.copyIn(sql, in)
          val end = System.nanoTime()
          logInfo(s"Copied $nums row(s) to Greenplum," +
            s" time taken: ${(end - start) / math.pow(10, 9)}s")
          committed = true
          accumulator.add(1L)
        } finally {
          in.close()
        }
      } finally {
        if (!committed) {
          if (!isAppend) {
            val sql = s"DROP TABLE IF EXISTS $tempTable"
            executeStatement(conn, sql)
          }
          conn.close()
        } else {
          try {
            conn.close()
          } catch {
            case e: Exception => logWarning("Transaction succeeded, but closing failed", e)
          }
        }
      }
    }

    if (accumulator.value == partNum) {
      if (!isAppend) {
        var committed = false
        val conn = JdbcUtils.createConnectionFactory(options)()
        try {
          val dropTableSql = s"DROP TABLE IF EXISTS ${options.table}"
          executeStatement(conn, dropTableSql)

          val renameTableSql = s"ALTER TABLE $tempTable RENAME TO ${options.table}"
          committed = executeStatement(conn, renameTableSql)
        } finally {
          if (!committed) {
            val sql = s"DROP TABLE IF EXISTS $tempTable"
            executeStatement(conn, sql)
            conn.close()
          } else {
            // The stage must succeed.  We cannot propagate any exception close() might throw.
            try {
              conn.close()
            } catch {
              case e: Exception => logWarning("Transaction succeeded, but closing failed", e)
            }
          }
        }
      }
    } else {
      if (!isAppend) {
        val conn = JdbcUtils.createConnectionFactory(options)()
        val sql = s"DROP TABLE IF EXISTS $tempTable"
        executeStatement(conn, sql)
        conn.close()
      }
    }
  }

  def executeStatement(conn: Connection, sql: String): Boolean = {
    val statement = conn.createStatement()
    try {
      statement.executeUpdate(sql)
      true
    } finally {
      statement.close()
    }
  }
}
