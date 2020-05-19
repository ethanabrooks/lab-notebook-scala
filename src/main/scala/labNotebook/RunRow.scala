package labNotebook

import java.sql.Blob

import doobie.util.{Get, Put}

import scala.reflect.runtime.universe._

case class RunRow(
//                   checkpoint: Option[Array[Byte]],
                   commitHash: String,
                   config: String,
                   configScript: Option[String],
                   containerId: String,
                   description: String,
//                   events: Option[Array[Byte]],
                   killScript: String,
                   launchScript: String,
                   name: String,
                 )
//    (
//      checkpoint,
//      commit,
//      config,
//      configScript,
//      containerId,
//      description,
//      events,
//      killScript,
//      launchScript,
//      name
//          )


object RunRow {
  val fields: List[String] = typeOf[RunRow].members.collect {
    case m: MethodSymbol if m.isCaseAccessor => m.name.toString
  }.toList
}

