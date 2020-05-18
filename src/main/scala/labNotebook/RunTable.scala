package labNotebook

import slick.jdbc.H2Profile.api._

import java.sql.Blob

class RunTable(tag: Tag) extends Table[RunRow](tag, "Runs") {
  def checkpoint = column[Option[Blob]]("checkpoint")

  def commit = column[String]("commit")

  def config = column[String]("config")

  def configScript = column[Option[String]]("configScript")

  def containerId = column[String]("containerId")

  def description = column[String]("description")

  //  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def events = column[Option[Blob]]("events")

  def killScript = column[String]("killScript")

  def launchScript = column[String]("launchScript")

  def name = column[String]("name", O.PrimaryKey)


  def * =
    (
      checkpoint,
      commit,
      config,
      configScript,
      containerId,
      description,
      events,
      killScript,
      launchScript,
      name,
      )
      .mapTo[RunRow]

  def stringToField(string: String): Option[Rep[_ >: String with Option[String] <: Serializable]] = {
    string match {
      case "commit" => Some(this.commit)
      case "config" => Some(this.config)
      case "configScript" => Some(this.configScript)
      case "containerId" => Some(this.containerId)
      case "description" => Some(this.description)
      case "killScript" => Some(this.killScript)
      case "launchScript" => Some(this.launchScript)
      case "name" => Some(this.name)
      case _ => None
    }
  }
}

