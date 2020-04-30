package lab_notebook
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import io.circe.parser._
import org.rogach.scallop._
import os._
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.sys.process._

case class RunRow(
    commit: String,
    config: String,
    container_id: String,
    description: String,
    id: Long = 0L,
    name: String,
    script: String,
)

class RunTable(tag: Tag) extends Table[RunRow](tag, "Runs") {
  def commit = column[String]("commit")
  def config = column[String]("config")
  def container_id = column[String]("container_id")
  def description = column[String]("description")
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def script = column[String]("script")
  def * =
    (commit, config, container_id, description, id, name, script).mapTo[RunRow]
}

class Conf(args: Seq[String]) extends ScallopConf(args) {
  private val pathConverter: ValueConverter[Path] =
    singleArgConverter(Path(_))
  val db_path = opt[String](required = true)
  val wait_time = opt[Int](default = Some(2))
  val _new = new Subcommand("new") {
    val name_prefix = opt[String]()
    val config_map = opt(required = true)(pathConverter)
    val run_script = opt(required = true)(pathConverter)
    val kill_script = opt(required = true)(pathConverter)
    val commit = opt[String](required = true)
    val description = opt[String](required = true)
  }
  addSubcommand(_new)
  val rm = new Subcommand("rm") {
    val pattern = opt[String](required = true)
    val kill_script = opt(required = true)(pathConverter)
  }
  addSubcommand(rm)
  val ls = new Subcommand("ls") {
    val pattern = opt[String](required = true)
  }
  addSubcommand(ls)
  val lookup = new Subcommand("lookup") {
    val field = opt[String](required = true)
    val pattern = opt[String](required = true)
  }
  addSubcommand(lookup)
  verify()
}

object LabNotebook extends IOApp {
  type DatabaseDef = H2Profile.backend.DatabaseDef

  object Command {
    def run(run_script: Path,
            kill_script: Path,
            config: String): Resource[IO, String] = {
      Resource.make {
        IO(s"$run_script $config" !!)
      } { id =>
        IO(println(s"run id: $id"))
          .handleErrorWith(e => kill(kill_script, id) >> IO(println(e))) // release
      }
    }
    def kill(script: Path, id: String): IO[Unit] =
      IO(f"$script $id" !) >> IO(println(s"Executed kill script: $script"))
  }

  implicit class DB(db: DatabaseDef) {
    def execute[X](action: DBIO[X], wait_time: Duration): IO[X] = {
      IO {
        Await.result(db.run(action), wait_time)
      }
    }
  }

  object DB {
    def connect(path: String): Resource[IO, DatabaseDef] =
      Resource.make {
        IO(
          Database.forURL(url = s"jdbc:h2:$path",
                          driver = "org.h2.Driver",
                          keepAliveConnection = true)
        ) // build
      } { db =>
        IO(db.close()).handleErrorWith(e => IO(println(e))) // release
      }

  }

  override def run(args: List[String]): IO[ExitCode] = {
    val conf = new Conf(args)
    val table = TableQuery[RunTable]
    val wait_time = conf.wait_time().seconds
    val lookup_query = (pattern: String) =>
      table
        .filter(_.name like (pattern: String))

    conf.subcommand match {
      case Some(conf._new) =>
        val c = conf._new
        for {

          // read config_map
          map_string <- IO(os.read(c.config_map()))
          string_map <- IO.fromEither(decode[Map[String, String]](map_string))

          // collect resources
          resources = for {
            commands_resource <- string_map.toList.traverse {
              case (name, config) =>
                for {
                  id <- Command
                    .run(c.run_script(), c.kill_script(), config)
                } yield (name, id, config)
            }
            db_resource <- DB.connect(conf.db_path())
          } yield (commands_resource, db_resource)

          // run commands
          _ <- resources.use {
            case (tuples, db) =>
              val new_entries = for {
                (name, container_id, config) <- tuples
              } yield
                RunRow(
                  commit = c.commit(),
                  config = config,
                  container_id = container_id,
                  name = name,
                  script = c.run_script().toString(),
                  description = c.description(),
                )
              db.execute(
                table.schema.createIfNotExists >> (table ++= new_entries),
                wait_time)
              IO.unit
          }
        } yield ExitCode.Success
      case Some(conf.rm) => {
        val rm = conf.rm
        val _lookup_query = lookup_query(rm.pattern())
        for {
          ids <- DB.connect(conf.db_path()).use { db =>
            {
              val (ids: IO[Seq[String]]) =
                db.execute(_lookup_query.map(_.container_id).result, wait_time)
              db.execute(_lookup_query.delete, wait_time)
              ids
            }
          }
          _ <- ids.toList.traverse(Command.kill(rm.kill_script(), _))
        } yield ExitCode.Success
      }
      case Some(conf.ls) => {
        val ls = conf.ls
        val _lookup_query = lookup_query(ls.pattern())
        for {
          ids <- DB.connect(conf.db_path()).use { db =>
            {
              db.execute(_lookup_query.map(_.name).result, wait_time)
            }
          }
          _ <- ids.toList.traverse(id => IO(println(id)))
        } yield ExitCode.Success
      }
      case Some(conf.lookup) => {
        val _lookup_query = lookup_query(conf.lookup.pattern())
        for {
          ids <- DB.connect(conf.db_path()).use { db =>
            db.execute(_lookup_query.map(_.name).result, wait_time)
          }
          _ <- ids.toList.traverse(id => IO(println(id)))
        } yield ExitCode.Success
      }
      case _ => IO(ExitCode.Success)
    }
  }
}
