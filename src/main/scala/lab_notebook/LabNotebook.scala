package lab_notebook
import cats.Monad
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import org.rogach.scallop._
import os._
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api._
import io.circe._, io.circe.generic.auto._, io.circe.parser._, io.circe.syntax._
import io.circe._, io.circe.parser._
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.sys.process._

case class Run(name: String,
               script: String,
               config: String,
               commit: String,
               description: String,
               id: Long = 0L)

class RunTable(tag: Tag) extends Table[Run](tag, "message") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def name = column[String]("name")
  def script = column[String]("script")
  def config = column[String]("config")
  def commit = column[String]("commit")
  def description = column[String]("description")
  def * = (name, script, config, commit, description, id).mapTo[Run]
}

class Conf(args: Seq[String]) extends ScallopConf(args) {
  private val pathConverter: ValueConverter[Path] =
    singleArgConverter(Path(_, base = os.pwd))
  val _new = new Subcommand("new") {
    val name_prefix = opt[String]()
    val config_map = opt[String](required = true)
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
    def collect(
        commands: List[Resource[IO, String]]): Resource[IO, List[String]] =
      commands.traverse(x => x)
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

  def collect_resources(commands: List[Resource[IO, String]],
                        path: Path): Resource[IO, (List[String], DatabaseDef)] =
    for {
      commands <- Command.collect(commands)
      db <- DB.connect(path)
    } yield (commands, db)

  def create_runs(resources: Resource[IO, (List[String], DatabaseDef)],
                  action: DBIO[Unit],
                  wait_time: Duration): IO[Unit] =
    resources.use {
      case (_, db) => db.execute[Unit](action, wait_time)
    }

  def create_runs2(commands: List[Resource[IO, String]],
                   path: Path,
                   action: DBIO[Unit],
                   wait_time: Duration): IO[Unit] = {
    val resources = for {
      commands <- Command.collect(commands)
      db <- DB.connect(path)
    } yield (commands, db)
    resources.use {
      case (_, db) => db.execute[Unit](action, wait_time)
    }
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val conf = new Conf(args)
    conf.subcommand match {
      case Some(conf._new) =>
        val run_script = conf._new.run_script()
        val kill_script = conf._new.kill_script()
        val db_path = conf._new.db_path()
        val wait_time = conf._new.wait_time().seconds
        val query = TableQuery[RunTable]
        val x: IO[ExitCode] = for {
          config_map <- IO.fromEither(
            decode[Map[String, String]](conf._new.config_map()))
          commands: List[Resource[IO, String]] = config_map.values.map(
            config_path =>
              Command.run(run_script, kill_script, os.pwd / config_path))
          resources: Resource[IO, (List[String], DatabaseDef)] = for {
            commands_resource <- commands.traverse(x => x)
            db_resource <- DB.connect(db_path)
          } yield (commands_resource, db_resource)
          val new_entries = for ((name, config) <- config_map)
            yield
              Run(
                name = conf._new.name_prefix + name,
                script = run_script.toString(),
                commit = conf._new.commit(),
                config = os.read(config),
                description = conf._new.description(),
              )
          val action = query.schema.createIfNotExists >> (query ++= new_entries)
          _ <- resources.use {
            case (_, db) => db.execute[Unit](action, wait_time)
          }

        } yield config_map
        val decodedFoo = decode[Foo](json)

        val resources = for {
          commands <- Command.collect(commands)
          db <- DB.connect(path)
        } yield (commands, db)
        resources.use {
          case (_, db) => db.execute[Unit](action, wait_time)
        }
        val run_configs = os.walk(conf._new.config_dir())
        val script = conf._new.script().toString()
        val new_entries = for (p <- run_configs)
          yield
            Run(
              name = conf._new.name_prefix + p.baseName,
              script = script,
              commit = conf._new.commit(),
              config = os.read(p),
              description = conf._new.description(),
            )
        val query = TableQuery[RunTable]
        val wait_time = conf._new.wait_time().seconds
        val sameActions = query.schema.createIfNotExists >> (query ++= new_entries)
        for {
          db <- connect(conf._new.db_path())
          cmds <- db.execute()

        } yield ExitCode.Success
      //      for { config <- run_configs
      //        result <- run_config(config.toString())
      //      }
      case Some(conf.kill) => println("hello")
      case _               => println("wut")
    }
    ExitCode.Success
  }
}
