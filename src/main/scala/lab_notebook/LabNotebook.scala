package lab_notebook

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import io.circe.parser.decode
import io.github.vigoo.prox.{
  JVMProcessRunner,
  Process,
  ProcessResult,
  ProcessRunner
}
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

  object DB {
    def connect(path: String): Resource[IO, DatabaseDef] =
      Resource.make {
        IO(
          Database.forURL(url = s"jdbc:h2:$path",
                          driver = "org.h2.Driver",
                          keepAliveConnection = true)
        )
      } { db =>
        IO(db.close())
      }
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
        val run_script = conf._new.run_script().toString()

        def read_config_map(): IO[Map[String, String]] = {
          for {
            read <- IO(os.read(conf._new.config_map()))
            map <- IO.fromEither(decode[Map[String, String]](read))
          } yield map
        }

        Blocker[IO]
          .use { blocker =>
            def get_container_ids(
                config_map: Map[String, String]): IO[List[String]] = {
              for {
                _ <- putStrLn("Launching run_scripts...") >> readLn
                fibers <- config_map.values.toList.traverse { config =>
                  val run_proc =
                    Process[IO](run_script, List(config))
                  val capture_output = fs2.text.utf8Decode[IO]
                  val proc = run_proc ># capture_output
                  Concurrent[IO].start(proc.run(blocker))
                }
                _ <- putStrLn("Joining run_scripts...") >> readLn
                results <- fibers
                  .map(_.join)
                  .traverse((result: IO[ProcessResult[String, Unit]]) =>
                    result >>= (r => IO(r.output)))
                _ <- putStrLn("Joined run_scripts...") >> readLn
              } yield results
            }

            for {
              config_map <- read_config_map()
              result <- get_container_ids(config_map)
                .bracketCase { (container_ids: List[String]) =>
                  val new_entries: List[RunRow] = for {
                    (container_id, (name, config)) <- container_ids zip config_map
                  } yield
                    RunRow(
                      commit = conf._new.commit(),
                      config = config,
                      container_id = container_id,
                      name = conf._new.name_prefix.getOrElse("") + name,
                      script = conf._new.run_script().toString(),
                      description = conf._new.description(),
                    )
                  val action = table.schema.createIfNotExists >> (table ++= new_entries)

                  putStrLn("Not yet connected") >> readLn
//                  >>
//                    DB.connect(conf.db_path())
//                      .use { (db: DatabaseDef) =>
//                        putStrLn("Inserting new runs...") >> readLn >> IO
//                          .fromFuture(IO(db.run(action)))
//                      }
                } {
                  case (_, Completed) =>
                    putStrLn("IO operations complete.")
                  case (container_ids, _) =>
                    putStrLn(s"KILL IO $container_ids") // TODO: run kill script
                }
            } yield result
          } as ExitCode.Success
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
