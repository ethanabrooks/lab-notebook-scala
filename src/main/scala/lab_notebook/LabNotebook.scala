package lab_notebook

import cats.effect.Console.io.putStrLn
import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect.{Blocker, Concurrent, ExitCode, Fiber, IO, IOApp, Resource}
import cats.implicits._
import io.circe.parser.decode
import org.rogach.scallop.{
  ScallopConf,
  Subcommand,
  ValueConverter,
  singleArgConverter
}
import os.Path
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import io.github.vigoo.prox
import io.github.vigoo.prox.{
  JVMProcessRunner,
  Process,
  ProcessResult,
  ProcessRunner,
  syntax
}

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
    val name_prefix = opt[String](required = false)
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
      val process = Process[IO]("echo", List("hello"))
      //      Process[IO](run_script.toString(), List(config))
      //      Blocker[IO].use { blocker =>
      //        process.start(blocker).use { fiber =>
      //          fiber.join
      //        }
      //      }
      Resource.make {
        IO(s"$run_script $config")
      } { id =>
        putStrLn(s"run id: $id")
      //          .handleErrorWith(e => kill(kill_script, id) >> putStrLn(s"$e")) // release
      }
    }

    def kill(script: Path, id: String): IO[Unit] =
      putStrLn(f"$script $id") >> putStrLn(s"Executed kill script: $script")
  }

  implicit class DB(db: DatabaseDef) {
    def execute[X](action: DBIO[X], wait_time: Duration): IO[X] = {
      IO {
        Await.result(db.run(action), wait_time)
      }
    }
  }

  object DB {
    def connect(path: String): IO[DatabaseDef] =
      IO(
        Database.forURL(url = s"jdbc:h2:$path",
                        driver = "org.h2.Driver",
                        keepAliveConnection = true)
      ) // build
    //      } { db =>
    //        IO(db.close()).handleErrorWith(e => putStrLn(s"$e")) // release
    //      }

  }

  override def run(args: List[String]): IO[ExitCode] = {
    val conf = new Conf(args)
    val table = TableQuery[RunTable]
    val wait_time = conf.wait_time().seconds
    val lookup_query = (pattern: String) =>
      table
        .filter(_.name like (pattern: String))
    implicit val runner: ProcessRunner[IO] = new JVMProcessRunner

    conf.subcommand match {
      case Some(conf._new) =>
        val c = conf._new
        val run_script = c.run_script().toString()
        for {

          // read config_map
          map_string <- IO(os.read(c.config_map()))
          string_map <- IO.fromEither(decode[Map[String, String]](map_string))
          _container_ids: IO[List[String]] = Blocker[IO].use { b =>
            val _fibers: IO[List[Fiber[IO, ProcessResult[String, Unit]]]] =
              string_map.values.toList.traverse { config =>
                val run_script_process = Process[IO](run_script, List(config))
                val proc = run_script_process ># fs2.text.utf8Decode[IO]
                Concurrent[IO].start(proc.run(b))
              }
            for {
              fibers <- _fibers
              list_io_results: List[IO[ProcessResult[String, Unit]]] = for (fiber <- fibers)
                yield fiber.join
              _results: IO[List[String]] = list_io_results.traverse {
                (_result: IO[ProcessResult[String, Unit]]) =>
                  for {
                    result <- _result
                  } yield result.output
              }
              results <- _results
            } yield results
          }
          combined_io: IO[(DatabaseDef, List[(String, (String, String))])] = for {
            container_ids <- _container_ids
            zipped: List[(String, (String, String))] = container_ids.zip(
              string_map)
            db <- DB.connect(conf.db_path())
          } yield (db, zipped)
          // run commands
          _ <- combined_io.bracketCase {
            case (db, tuples) =>
              val dumb1: DatabaseDef = db
              val dumb2: List[(String, (String, String))] = tuples
              val new_entries: List[RunRow] = for {
                (container_id, (name, config)) <- tuples
              } yield
                RunRow(
                  commit = c.commit(),
                  config = config,
                  container_id = container_id,
                  name = c.name_prefix.getOrElse("") + name,
                  script = c.run_script().toString(),
                  description = c.description(),
                )
              db.execute(
                table.schema.createIfNotExists >> (table ++= new_entries),
                wait_time)
          } {
            case ((db, _), Completed) =>
              IO(db.close())
            case ((db, tuples), _) =>
              IO(db.close()) >> IO(println("kill")) // TODO: run kill script
          }
        } yield ExitCode.Success
      //      case Some(conf.rm) => {
      //        val rm = conf.rm
      //        val _lookup_query = lookup_query(rm.pattern())
      //        for {
      //          ids <- DB.connect(conf.db_path()).use { db =>
      //            {
      //              val (ids: IO[Seq[String]]) =
      //                db.execute(_lookup_query.map(_.container_id).result, wait_time)
      //              db.execute(_lookup_query.delete, wait_time)
      //              ids
      //            }
      //          }
      //          _ <- ids.toList.traverse(Command.kill(rm.kill_script(), _))
      //        } yield ExitCode.Success
      //      }
      //      case Some(conf.ls) => {
      //        val ls = conf.ls
      //        val _lookup_query = lookup_query(ls.pattern())
      //        for {
      //          ids <- DB.connect(conf.db_path()).use { db =>
      //            {
      //              db.execute(_lookup_query.map(_.name).result, wait_time)
      //            }
      //          }
      //          _ <- ids.toList.traverse(id => putStrLn(id))
      //        } yield ExitCode.Success
      //      }
      //      case Some(conf.lookup) => {
      //        val _lookup_query = lookup_query(conf.lookup.pattern())
      //        for {
      //          ids <- DB.connect(conf.db_path()).use { db =>
      //            db.execute(_lookup_query.map(_.name).result, wait_time)
      //          }
      //          _ <- ids.toList.traverse(id => putStrLn(id))
      //        } yield ExitCode.Success
      //      }
      case _ => IO(ExitCode.Success)
    }
  }
}
