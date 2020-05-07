package lab_notebook
import java.nio.file.{Path, Paths}
import java.sql.Blob

import cats.Monad
import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImpl
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}
import org.rogach.scallop.{
  ScallopConf,
  ScallopOption,
  Subcommand,
  ValueConverter,
  singleArgConverter
}
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api._

import scala.io.BufferedSource
import scala.language.postfixOps
import scala.util.Try

case class RunRow(
    commit: String,
    config: String,
    containerId: String,
    description: String,
    name: String,
    launchScript: String,
    configScript: Option[String],
    checkpoint: Option[Blob],
    events: Option[Blob],
)

class RunTable(tag: Tag) extends Table[RunRow](tag, "Runs") {
  def commit = column[String]("commit")

  def config = column[String]("config")

  def containerId = column[String]("containerId")

  def description = column[String]("description")

  //  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def name = column[String]("name", O.PrimaryKey)

  def launchScript = column[String]("launchScript")

  def configScript = column[Option[String]]("configScript")

  def checkpoint = column[Option[Blob]]("checkpoint")

  def events = column[Option[Blob]]("events")

  def * =
    (commit,
     config,
     containerId,
     description,
     name,
     launchScript,
     configScript,
     checkpoint,
     events)
      .mapTo[RunRow]
}

class Conf(args: Seq[String]) extends ScallopConf(args) {
  private val pathConverter: ValueConverter[Path] =
    singleArgConverter(Paths.get(_))

  def envPath(s: String): Option[Path] = Some(Paths.get(sys.env(s)))

  val dbPath: ScallopOption[Path] =
    opt(default = envPath("RUN_DB_PATH"))(pathConverter)
  val New = new Subcommand("new") {
    val name: ScallopOption[String] = opt(required = true)
    val configScript: ScallopOption[Path] =
      opt(default = envPath("RUN_CONFIG_SCRIPT"))(pathConverter)
    val numRuns: ScallopOption[Int] = opt()
    val config: ScallopOption[String] = opt()
    mutuallyExclusive(config, configScript)
    mutuallyExclusive(config, numRuns)
    val launchScript: ScallopOption[Path] =
      opt(default = envPath("RUN_LAUNCH_SCRIPT"))(pathConverter)
    val killScript: ScallopOption[Path] =
      opt(default = envPath("RUN_KILL_SCRIPT"))(pathConverter)
    val description: ScallopOption[String] = opt()
    for (path <- List(launchScript, killScript, configScript))
      validatePathExists(path)
  }
  addSubcommand(New)
  val rm = new Subcommand("rm") {
    val pattern: ScallopOption[String] = opt(required = true)
    val killScript: ScallopOption[Path] = opt(required = true)(pathConverter)
  }
  addSubcommand(rm)
  val lookup = new Subcommand("lookup") {
    val field: ScallopOption[String] =
      opt(
        required = true,
        validate = s => {
          Try {
            classOf[RunRow].getDeclaredField(s)
          }.isSuccess
        }
      )
    val pattern: ScallopOption[String] = opt(required = true)
  }
  addSubcommand(lookup)
  verify()
}

object LabNotebook extends IOApp {
  type DatabaseDef = H2Profile.backend.DatabaseDef
  private val table = TableQuery[RunTable]
  private val captureOutput: Pipe[IO, Byte, String] = fs2.text.utf8Decode[IO]
  implicit val runner: ProcessRunner[IO] = new JVMProcessRunner

  implicit class DB(db: DatabaseDef) {
    def execute[X](action: DBIO[X]): IO[X] = {
      IO.fromFuture(IO(db.run(action)))
    }
  }

  object DB {
    def connect(path: Path): Resource[IO, DatabaseDef] =
      Resource.make {
        IO(
          Database.forURL(url = s"jdbc:h2:$path",
                          driver = "org.h2.Driver",
                          keepAliveConnection = true))
      } { db =>
        IO(db.close())
      }
  }

  implicit class ConfigMap(map: Map[String, String]) {
    def print(): IO[List[Unit]] = {
      map.toList.traverse {
        case (name, config) =>
          putStrLn(name + ":") >>
            putStrLn(config)
      }
    }
  }

  object ConfigMap {
    def build(configScript: Path, numRuns: Int, name: String,
    )(implicit blocker: Blocker): IO[Map[String, String]] = {
      val configProc = Process[IO](configScript.toString) ># captureOutput
      val procResults =
        Monad[IO].replicateA(numRuns, configProc.run(blocker))
      procResults >>= { results =>
        val pairs =
          for ((result, i) <- results.zipWithIndex)
            yield (s"$name$i", result.output)
        IO.pure(pairs.toMap)
      }
    }
  }

  def getCommit(implicit blocker: Blocker): IO[String] = {
    val proc: ProcessImpl[IO] =
      Process[IO]("git", List("rev-parse", "HEAD"))
    (proc ># captureOutput).run(blocker) >>= (c => IO.pure(c.output))
  }

  def getCommitMessage(implicit blocker: Blocker): IO[String] = {
    val proc: ProcessImpl[IO] =
      Process[IO]("git", List("log", "-1", "--pretty=%B"))
    (proc ># captureOutput).run(blocker) >>= (m => IO.pure(m.output))
  }

  def getDescription(description: Option[String])(
      implicit blocker: Blocker): IO[String] = {
    description match {
      case Some(d) => IO.pure(d)
      case None    => getCommitMessage(blocker)
    }
  }

  def launchProc(script: Path)(config: String): ProcessImpl[IO] = {
    Process[IO](script.toString, List(config))
  }

  def killProc(script: Path)(ids: List[String]): ProcessImpl[IO] = {
    Process[IO](script.toString, ids)
  }

  def launchRuns(configMap: Map[String, String], launchScript: Path)(
      implicit blocker: Blocker): IO[List[String]] =
    for {
      fibers <- configMap.values.toList.traverse { config =>
        val proc = launchProc(script = launchScript)(config = config) ># captureOutput
        Concurrent[IO].start(proc.run(blocker))
      }
      results <- fibers
        .map(_.join)
        .traverse(_ >>= (r => IO.pure(r.output)))
    } yield results

  def insertNewRuns(launchProc: String => ProcessImpl[IO],
                    commit: String,
                    description: String,
                    configScript: Option[String],
                    configMap: Map[String, String])(containerIds: List[String])(
      implicit dbPath: Path,
  ): IO[List[_]] = {
    val newRows = for {
      (id, (name, config)) <- containerIds zip configMap
    } yield
      RunRow(
        commit = commit,
        config = config,
        containerId = id,
        name = name,
        launchScript = launchProc(config).toString,
        description = description,
        configScript = configScript,
        checkpoint = None,
        events = None,
      )
    val createIfNotExists = table.schema.createIfNotExists
    val checkExisting =
      table
        .filter(_.name inSet configMap.keys)
        .map(_.name)
        .result
    val upserts = for (row <- newRows) yield table.insertOrUpdate(row)
    DB.connect(dbPath).use { db =>
      for {
        existing <- db.execute(createIfNotExists >> checkExisting)
        checkIfExisting = if (existing.isEmpty) {
          IO.unit
        } else {
          putStrLn("Overwrite the following rows?") >>
            existing.toList.traverse(putStrLn) >>
            readLn
        }
        r <- checkIfExisting >> db.execute(DBIO.sequence(upserts))
      } yield r
    }
  }

  def newRuns(configMap: Map[String, String],
              killProc: List[String] => ProcessImpl[IO],
              launchRuns: IO[List[String]],
              insertNewRuns: List[String] => IO[List[_]])(implicit
                                                          blocker: Blocker,
  ): IO[ExitCode] = {
    launchRuns
      .bracketCase {
        insertNewRuns
      } {
        case (_, Completed) =>
          putStrLn("IO operations complete.")
        case (containerIds: List[String], _) =>
          killProc(containerIds).run(blocker).void
      } as ExitCode.Success
  }

  def lookupRuns(field: String, pattern: String)(
      implicit dbPath: Path): IO[ExitCode] = {
    for {

      ids <- DB.connect(dbPath).use { db =>
        db.execute(
          table
            .filter(_.name like pattern)
            .map((e: RunTable) => {
              field match {
                case "commit"      => e.commit
                case "config"      => e.config
                case "containerId" => e.containerId
                case "description" => e.description
                case "name"        => e.name
                case "script"      => e.launchScript
              }
            })
            .result)
      }
      _ <- if (ids.isEmpty) {
        putStrLn(s"No runs match pattern $pattern")
      } else {
        ids.toList.traverse(putStrLn)
      }
    } yield ExitCode.Success

  }

  def readConfigScript(path: Option[Path]): IO[Option[String]] = path match {
    case None => IO.pure(None)
    case Some(p) =>
      Resource
        .fromAutoCloseable(IO(scala.io.Source.fromFile(p.toFile)))
        .use((s: BufferedSource) => IO(Some(s.mkString)))

  }

  def rmRuns(pattern: String, killProc: List[String] => ProcessImpl[IO])(
      implicit dbPath: Path): IO[ExitCode] = {
    DB.connect(dbPath).use { db =>
      val query = table.filter(_.name like pattern)
      db.execute(query.result) >>= { (matches: Seq[RunRow]) =>
        val ids = matches.map(_.containerId).toList
        if (matches.isEmpty) {
          putStrLn(s"No runs match pattern $pattern")
        } else {
          putStrLn("Delete the following rows?") >>
            matches.map(_.name).toList.traverse(putStrLn) >>
            readLn >>
            Blocker[IO].use(killProc(ids).run(_)) >>
            db.execute(query.delete)
        }
      }
    } >> IO.pure(ExitCode.Success)

  }

  override def run(args: List[String]): IO[ExitCode] = {
    val conf = new Conf(args)
    implicit val dbPath: Path = conf.dbPath()

    conf.subcommand match {
      case Some(conf.New) =>
        val name = conf.New.name()
        val launchScript = conf.New.launchScript()
        Blocker[IO].use(b => {
          implicit val blocker: Blocker = b
          val configScript = conf.New.configScript.toOption
          for {
            configMap <- (conf.New.config.toOption,
                          configScript,
                          conf.New.numRuns.toOption) match {
              case (Some(config), _, None) =>
                IO.pure(Map(name -> config))
              case (None, Some(configScript), Some(numRuns)) =>
                ConfigMap.build(configScript = configScript,
                                numRuns = numRuns,
                                name = name)
              case _ =>
                IO.raiseError(new RuntimeException(
                  "--config and --num-runs are mutually exclusive argument groups."))
            }
            _ <- putStrLn("Create the following runs?") >>
              configMap.print() >>
              readLn
            commit <- getCommit
            description <- getDescription(conf.New.description.toOption)
            configScript <- readConfigScript(configScript)
            result <- newRuns(
              configMap = configMap,
              killProc = killProc(conf.New.killScript()),
              launchRuns = launchRuns(configMap, launchScript),
              insertNewRuns = insertNewRuns(
                launchProc = launchProc(launchScript),
                commit = commit,
                description = description,
                configScript = configScript,
                configMap = configMap,
              )
            )
          } yield result
        })

      case Some(conf.lookup) =>
        lookupRuns(field = conf.lookup.field(), pattern = conf.lookup.pattern())
      case Some(conf.rm) =>
        rmRuns(killProc = killProc(conf.rm.killScript()),
               pattern = conf.rm.pattern())
      case _ => IO.pure(ExitCode.Success)
    }
  }
}
