package lab_notebook

import cats.effect.Console.io.putStrLn
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import io.circe.parser.decode
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}
import org.rogach.scallop.{
  ScallopConf,
  ScallopOption,
  Subcommand,
  ValueConverter,
  singleArgConverter
}
import os.Path
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api._

import scala.language.postfixOps

case class RunRow(
    commit: String,
    config: String,
    containerId: String,
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
  val dbPath: ScallopOption[String] = opt(required = true)
  val New = new Subcommand("new") {
    val namePrefix: ScallopOption[String] = opt(required = false)
    val configMap: ScallopOption[Path] = opt(required = true)(pathConverter)
    val runScript: ScallopOption[Path] = opt(required = true)(pathConverter)
    val killScript: ScallopOption[Path] = opt(required = true)(pathConverter)
    val commit: ScallopOption[String] = opt(required = true)
    val description: ScallopOption[String] = opt(required = true)
  }
  addSubcommand(New)
  val rm = new Subcommand("rm") {
    val pattern: ScallopOption[String] = opt(required = true)
    val killScript: ScallopOption[Path] = opt(required = true)(pathConverter)
  }
  addSubcommand(rm)
  val ls = new Subcommand("ls") {
    val pattern: ScallopOption[String] = opt(required = true)
  }
  addSubcommand(ls)
  val lookup = new Subcommand("lookup") {
    val field: ScallopOption[String] = opt(required = true)
    val pattern: ScallopOption[String] = opt(required = true)
  }
  addSubcommand(lookup)
  verify()
}

object LabNotebook extends IOApp {
  type DatabaseDef = H2Profile.backend.DatabaseDef

  implicit class DB(db: DatabaseDef) {
    def execute[X](action: DBIO[X]): IO[X] = {
      IO.fromFuture(IO(db.run(action)))
    }
  }

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
    implicit val runner: ProcessRunner[IO] = new JVMProcessRunner
    val conf = new Conf(args)
    val table = TableQuery[RunTable]
    val lookupQuery = (pattern: String) =>
      table
        .filter(_.name like (pattern: String))

    conf.subcommand match {
      case Some(conf.New) =>
        val runScript = conf.New.runScript().toString()
        val killScript = conf.New.killScript().toString()

        def readConfigMap(): IO[Map[String, String]] = {
          for {
            read <- IO(os.read(conf.New.configMap()))
            map <- IO.fromEither(decode[Map[String, String]](read))
          } yield map
        }

        def insertNewRuns(containerIds: List[String],
                          configMap: Map[String, String]): IO[Option[Int]] = {
          val newEntries: List[RunRow] = for {
            (id, (name, config)) <- containerIds zip configMap
          } yield
            RunRow(
              commit = conf.New.commit(),
              config = config,
              containerId = id,
              name = conf.New.namePrefix.getOrElse("") + name,
              script = conf.New.runScript().toString(),
              description = conf.New.description(),
            )
          val action = table.schema.createIfNotExists >> (table ++= newEntries)
          DB.connect(conf.dbPath())
            .use {
              _.execute(action)
            }
        }

        val captureOutput = fs2.text.utf8Decode[IO]

        Blocker[IO]
          .use { blocker =>
            def getContainerIds(
                configMap: Map[String, String]): IO[List[String]] =
              for {
                _ <- putStrLn("Launching run scripts...")
                fibers <- configMap.values.toList.traverse { config =>
                  val runProc = Process[IO](runScript, List(config))
                  val proc = runProc ># captureOutput
                  Concurrent[IO].start(proc.run(blocker))
                }
                results <- fibers
                  .map(_.join)
                  .traverse(_ >>= (r => IO(r.output)))
              } yield results

            for {
              configMap <- readConfigMap()
              result <- getContainerIds(configMap)
                .bracketCase {
                  insertNewRuns(_, configMap)
                } {
                  case (_, Completed) =>
                    putStrLn("IO operations complete.")
                  case (containerIds: List[String], _) =>
                    containerIds
                      .traverse(id => {
                        Process[IO](killScript, List(id))
                          .run(blocker) >> putStrLn(s"Killed id $id")
                      })
                      .void
                } as ExitCode.Success
            } yield result
          }
      case Some(conf.lookup) =>
        for {
          ids <- DB.connect(conf.dbPath()).use { db =>
            for {
              _ <- putStrLn("here")
              r <- db.execute(
                lookupQuery(conf.lookup.pattern())
                  .map(e => {
                    e.name
                    //                  e.getClass
                    //                    .getDeclaredField(conf.lookup.field())
                    //                    .asInstanceOf[String]
                  })
                  .result)
              _ <- putStrLn(s"there: $r")
            } yield r
          }
          _ <- putStrLn("Here")
          _ <- ids.toList.traverse(putStrLn)
          _ <- putStrLn("THere")
        } yield ExitCode.Success
      case _ => IO(ExitCode.Success)
    }
  }
}

//      case Some(conf.rm) => {
//        val rm = conf.rm
//        val _lookup_query = lookup_query(rm.pattern())
//        for {
//          ids <- DB.connect(conf.dbPath()).use { db =>
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
//          ids <- DB.connect(conf.dbPath()).use { db =>
//            {
//              db.execute(_lookup_query.map(_.name).result, wait_time)
//            }
//          }
//          _ <- ids.toList.traverse(id => putStrLn(id))
//        } yield ExitCode.Success
//      }
