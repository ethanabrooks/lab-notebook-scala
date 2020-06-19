package labNotebook
import doobie.implicits._

import scala.reflect.runtime.universe._

case class Run(checkpoint: Option[Array[Byte]],
               commitHash: String,
               config: String,
               configScript: Option[String],
               containerId: String,
               description: String,
               events: Option[Array[Byte]],
               killScript: String,
               launchScript: String,
               name: String,
)

object Run {
  val fields: List[String] = typeOf[Run].members.collect {
    case m: MethodSymbol if m.isCaseAccessor => m.name.toString
  }.toList

  val createTable = sql"""CREATE TABLE IF NOT EXISTS runs(
                checkpoint BLOB DEFAULT NULL,
                commitHash VARCHAR(255) NOT NULL,
                config VARCHAR(1024) NOT NULL,
                configScript CLOB DEFAULT NULL,
                containerId VARCHAR(255) NOT NULL,
                description VARCHAR(1024) NOT NULL,
                events BLOB DEFAULT NULL,
                killScript CLOB NOT NULL,
                launchScript CLOB NOT NULL,
                name VARCHAR(255) NOT NULL PRIMARY KEY
              )
            """
}
