package labNotebook
import doobie.Update
import doobie.implicits._

import scala.reflect.runtime.universe._

case class RunRow(checkpoint: Option[Array[Byte]],
                  commitHash: String,
                  config: String,
                  configScript: Option[String],
                  containerId: String,
                  description: String,
                  events: Option[Array[Byte]],
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
                checkpoint BLOB DEFAULT NULL,
                commitHash VARCHAR(255) NOT NULL,
                config VARCHAR(1024) NOT NULL,
                configScript CLOB DEFAULT NULL,
                containerId VARCHAR(255) NOT NULL,
                description VARCHAR(1024) NOT NULL,
                events BLOB DEFAULT NULL,
                name VARCHAR(255) NOT NULL PRIMARY KEY
              )
            """
}
