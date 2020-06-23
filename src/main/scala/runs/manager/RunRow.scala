package runs.manager

import doobie.Update
import doobie.implicits._

import scala.reflect.runtime.universe._

case class RunRow(commitHash: String,
                  config: String,
                  configScript: Option[String],
                  containerId: String,
                  imageId: String,
                  description: String,
                  logDir: String,
                  name: String,
)

object RunRow {
  val fields: List[String] = typeOf[RunRow].members.collect {
    case m: MethodSymbol if m.isCaseAccessor => m.name.toString
  }.toList

  private val placeholders = RunRow.fields.map(_ => "?").mkString(",")
  val mergeCommand: Update[RunRow] =
    Update[RunRow](s"MERGE INTO runs KEY (name) values ($placeholders)")

  val createTable = sql"""CREATE TABLE IF NOT EXISTS runs(
                commitHash VARCHAR(255) NOT NULL,
                config VARCHAR(1024) NOT NULL,
                configScript CLOB DEFAULT NULL,
                containerId VARCHAR(255) NOT NULL,
                imageId VARCHAR(255) NOT NULL,
                description VARCHAR(1024) NOT NULL,
                logDir VARCHAR(255) NOT NULL,
                name VARCHAR(255) NOT NULL PRIMARY KEY
              )
            """
}
