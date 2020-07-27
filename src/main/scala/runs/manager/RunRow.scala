package runs.manager

import java.util.Date

import doobie.Update
import doobie.implicits._

import scala.reflect.runtime.universe._

case class RunRow(commitHash: String,
                  config: Option[String],
                  configScript: Option[String],
                  containerId: String,
                  imageId: String,
                  description: String,
                  name: String,
                  datetime: Date,
                  volume: String,
)

case class PartialRunRow(commitHash: String,
                         config: Option[String],
                         configScript: Option[String],
                         imageId: String,
                         description: String,
                         name: String,
                         datetime: Date,
                         volume: String,
) {
  def toRunRow(containerId: String): RunRow = {
    RunRow(
      commitHash = commitHash,
      config = config,
      configScript = configScript,
      containerId = containerId,
      imageId = imageId,
      description = description,
      name = name,
      datetime = datetime,
      volume = volume,
    )
  }
}

object RunRow {
  val fields: List[String] = typeOf[RunRow].members.collect {
    case m: MethodSymbol if m.isCaseAccessor => m.name.toString
  }.toList

  private val placeholders = RunRow.fields.map(_ => "?").mkString(",")
  val mergeCommand: Update[RunRow] =
    Update[RunRow](s"MERGE INTO runs KEY (name) values ($placeholders)")

  val createTable = sql"""CREATE TABLE IF NOT EXISTS runs(
                commitHash VARCHAR(255) NOT NULL,
                config VARCHAR(1024),
                configScript CLOB DEFAULT NULL,
                containerId VARCHAR(255) NOT NULL,
                imageId VARCHAR(255) NOT NULL,
                description VARCHAR(1024) NOT NULL,
                name VARCHAR(255) NOT NULL PRIMARY KEY,
                datetime TIMESTAMP NOT NULL,
                volume VARCHAR(255) NOT NULL
              )
            """
}
