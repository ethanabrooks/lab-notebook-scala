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

case class Run(
    commit: String,
    config: String,
    container_id: String,
    description: String,
    id: Long = 0L,
    name: String,
    script: String,
)

class RunTable(tag: Tag) extends Table[Run](tag, "Runs") {
  def commit = column[String]("commit")
  def config = column[String]("config")
  def container_id = column[String]("container_id")
  def description = column[String]("description")
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def script = column[String]("script")
  def * =
    (commit, config, container_id, description, id, name, script).mapTo[Run]
}

class Conf(args: Seq[String]) extends ScallopConf(args) {
  private val pathConverter: ValueConverter[Path] =
    singleArgConverter(Path(_, base = os.pwd))
  val _new = new Subcommand("new") {
    val name_prefix = opt[String]()
    val config_map = opt(required = true)(pathConverter)
    val run_script = opt(required = true)(pathConverter)
    val kill_script = opt(required = true)(pathConverter)
    val db_path = opt(required = true)(pathConverter)
    val commit = opt[String](required = true)
    val description = opt[String](required = true)
    val wait_time = opt[Int](default = Some(2))
  }
  addSubcommand(_new)
  val kill = new Subcommand("kill") {
    val dummy = opt(required = true)(pathConverter)
  }
  addSubcommand(kill)
  verify()
}

object LabNotebook extends IOApp {
  type DatabaseDef = H2Profile.backend.DatabaseDef

  object Command {
    def run(run_script: Path,
            kill_script: Path,
            config: Path): Resource[IO, String] =
      Resource.make {
        IO(s"$run_script $config" !!) // build
      } { id =>
        IO.unit.handleErrorWith(_ => IO(f"$kill_script $id" !)) // release
      }
  }

  implicit class DB(db: DatabaseDef) {
    def execute[X](action: DBIO[X], wait_time: Duration): IO[Unit] = {
      IO {
        Await.result(db.run(action), wait_time)
      }
    }
  }

  object DB {
    def connect(path: Path): Resource[IO, DatabaseDef] =
      Resource.make {
        IO(
          Database.forURL(url = s"jdbc:mysql:$path",
                          driver = "org.SQLite.Driver")
        ) // build
      } { db =>
        IO(db.close()).handleErrorWith(_ => IO.unit) // release
      }
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val conf = new Conf(args)
    conf.subcommand match {
      case Some(conf._new) =>
        val c = conf._new
        for {
          // read config_map
          map_string <- IO(os.read(c.config_map()))
          string_map <- IO.fromEither(decode[Map[String, String]](map_string))
          path_map = string_map.view.mapValues(Path(_))
          tuples: IO[List[(String, String, String)]] = string_map.toList
            .traverse {
              case (name, config_path) =>
                IO(name, config_path, os.read(Path(config_path)))
            }
          tup2: IO[List[String]] = string_map.toList
            .traverse {
              case (name, config_path) =>
                IO(os.read(Path(config_path)))
            }
//          y: List[String] <- tup2
          x: List[(String, Path, String)] = for {
            (a, b) <- path_map.toList
          } yield (a, b, "a")

          // collect resources
          resources = for {
            commands_resource <- x.traverse {
              case (name, config_path, n) =>
                for {
                  id <- Command
                    .run(c.run_script(), c.kill_script(), config_path)
                } yield (id, name, config_path)
            }
            db_resource <- DB.connect(c.db_path())
          } yield (commands_resource, db_resource)

          // generate insertion action
          config_reads <- string_map.toList
            .traverse {
              case (name, path) => IO(name, os.read(Path(path)))
            }
          new_entries = (ids: List[String]) =>
            for (((name, config), id) <- (config_reads zip ids))
              yield
                Run(
                  commit = c.commit(),
                  config = config,
                  container_id = id,
                  name = c.name_prefix + name,
                  script = c.run_script().toString(),
                  description = c.description(),
              )
          query = TableQuery[RunTable]
          action = (ids: List[String]) =>
            query.schema.createIfNotExists >> (query ++= new_entries(ids))

          // run commands
//          _ <- resources.use {
//            case (ids, db) =>
//              for {
//                (id, name, config_path) <- ids
//              } db.execute(action(ids), c.wait_time().seconds)
//          }

        } yield ExitCode.Success
      case Some(conf.kill) => IO(ExitCode.Success)
      case _               => IO(ExitCode.Success)
    }
  }
}
