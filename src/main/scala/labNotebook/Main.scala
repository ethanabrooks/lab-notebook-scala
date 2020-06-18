package labNotebook

import java.nio.file.Path
import cats.effect.Console.io.putStrLn
import cats.effect.{ExitCode, IO}
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import doobie._
import Fragments.in
import cats.Monad
import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{
  Blocker,
  Concurrent,
  ContextShift,
  ExitCode,
  IO,
  IOApp,
  Resource
}
import cats.implicits._
import doobie.implicits._
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImpl
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}

import scala.io.BufferedSource
import scala.language.postfixOps
import scala.language.postfixOps

object Main
    extends CommandIOApp(
      name = "run-manager",
      header = "Manages long-running processes (runs).",
    ) {

  val dbPathOpts: Opts[Path] =
    Opts.env[Path](
      "RUN_DB_PATH",
      """Path to database file (driver='com.mysql.jdbc.<this arg>'). 
        |Defaults to env variable RUN_DB_PATH.""".stripMargin,
    )

  val nameOpts: Opts[String] = Opts
    .option[String]("name", "Name and primary key of run", short = "n")

  val descriptionOpts: Opts[Option[String]] = Opts
    .option[String]("description", "Optional description of run", short = "d")
    .orNone

  val launchScriptOpts: Opts[Path] = Opts
    .env[Path](
      "RUN_LAUNCH_SCRIPT",
      """Path to script that launches run.
        |Run as $bash <this argument> <arguments produced by config?>""".stripMargin
    )

  val killOpts: Opts[Path] = Opts
    .env[Path](
      "RUN_KILL_SCRIPT",
      """Path to script that kills a run.
        |Run as $bash <this argument> <output of run script>.""".stripMargin
    )

  val configOpts: Opts[String] = Opts
    .argument[String]("config")

  val configScriptOpts: Opts[Path] = Opts
    .argument[Path]("config-script")

  val numRunsOpts: Opts[Int] = Opts
    .option[Int]("num-runs", "Number of runs to create.", short = "nr")

  abstract class NewMethod
  case class FromConfig(config: String) extends NewMethod
  case class FromConfigScript(configScript: Path, numRuns: Int)
      extends NewMethod

  val fromConfigOpts: Opts[FromConfig] =
    Opts.subcommand("config", "use a config string to configure runs") {
      configOpts.map(FromConfig)
    }
  val fromConfigScriptOpts: Opts[FromConfigScript] =
    Opts.subcommand("config-script", "use a config script to configure runs") {
      (configScriptOpts, numRunsOpts).mapN(FromConfigScript)
    }

  val dockerFileOpts: Opts[Option[String]] =
    Opts
      .option[String]("file", "The name of the Dockerfile.", short = "f")
      .orNone

  val pathOpts: Opts[String] =
    Opts.argument[String](metavar = "path")

  abstract class SubCommand

  case class New(name: String,
                 description: Option[String],
                 launchScript: Path,
                 killScript: Path,
                 newMethod: NewMethod)
      extends SubCommand
  val newOpts: Opts[New] =
    Opts.subcommand("new", "Launch new runs.") {
      (
        nameOpts,
        descriptionOpts,
        launchScriptOpts,
        killOpts,
        fromConfigOpts orElse fromConfigScriptOpts
      ).mapN(New)
    }

  case class BuildImage(dockerFile: Option[String], path: String)
      extends SubCommand
  val buildOpts: Opts[BuildImage] =
    Opts.subcommand("build", "Builds a docker image!") {
      (dockerFileOpts, pathOpts).mapN(BuildImage)
    }

  case class AllOpts(dbPath: Path, sub: SubCommand)
  val opts: Opts[AllOpts] = (dbPathOpts, newOpts orElse buildOpts).mapN(AllOpts)

  override def main: Opts[IO[ExitCode]] = opts.map {
    case AllOpts(dbPath, sub) =>
      sub match {
        case New(name, description, launchScript, killScript, newMethod) =>
          putStrLn(s"$dbPath $name  $description") as ExitCode.Success
        case BuildImage(dockerFile, path) =>
          putStrLn(s"build,$dbPath dockerfile: $dockerFile path: $path") as ExitCode.Success
      }
  }

  private implicit val cs: ContextShift[IO] =
    IO.contextShift(ExecutionContexts.synchronous)
  private val xa = Transactor.fromDriverManager[IO](
    driver = "com.mysql.jdbc.Driver",
    url = "jdbc:mysql::runs",
    user = "postgres",
    pass = "",
    Blocker
      .liftExecutionContext(ExecutionContexts.synchronous) // just for testing
  )

//  private val logger = LogManager.getLogger(Main.getClass);

  private val captureOutput: Pipe[IO, Byte, String] = fs2.text.utf8Decode[IO]
  implicit val runner: ProcessRunner[IO] = new JVMProcessRunner

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
    def build(configScript: Path, numRuns: Int, name: String)(
        implicit blocker: Blocker
    ): IO[Map[String, String]] = {
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

  def getDescription(
      description: Option[String]
  )(implicit blocker: Blocker): IO[String] = {
    description match {
      case Some(d) => IO.pure(d)
      case None    => getCommitMessage(blocker)
    }
  }

  def launchProc(script: Path)(config: String): ProcessImpl[IO] =
    Process[IO]("bash", script.toString :: List(config))

  def killProc(script: Path)(ids: List[String]): Process[IO, _, _] =
    Process[IO](script.toString, ids)

  def launchRuns(
      configMap: Map[String, String],
      launchProc: String => ProcessImpl[IO]
  )(implicit blocker: Blocker): IO[List[String]] =
    for {
      fibers <- configMap.values.toList.traverse { config =>
        val proc: Process[IO, String, _] = launchProc(config) ># captureOutput
        Concurrent[IO].start(proc.run(blocker))
      }
      results <- fibers
        .map(_.join)
        .traverse(_ >>= (r => IO.pure(r.output)))
    } yield results

  def insertNewRuns(launchScript: String,
                    killScript: String,
                    commit: String,
                    description: String,
                    configScript: Option[String],
                    configMap: Map[String, String],
  )(containerIds: List[String])(implicit dbPath: Path): IO[Int] = {
    val newRows = for {
      (id, (name, config)) <- containerIds zip configMap
    } yield
      RunRow(
        //        checkpoint = None,
        commitHash = commit,
        config = config,
        configScript = configScript,
        containerId = id,
        description = description,
        //        events = None,
        killScript = killScript,
        launchScript = launchScript,
        name = name,
      )
    configMap.keys.toList.toNel match {
      case None => IO.raiseError(new RuntimeException("Empty configMap"))
      case Some(names) =>
        val checkExisting =
          (fr"SELECT name FROM runs WHERE" ++ in(fr"name", names))
            .query[String]
            .to[List]
        val fields = RunRow.fields.mkString(",")
        val placeholders = RunRow.fields.map(_ => "?").mkString(",")
        val insert: doobie.ConnectionIO[Int] = Update[RunRow](
          s"REPLACE INTO runs ($fields) values ($placeholders)"
        ).updateMany(newRows)
        for {
          existing <- checkExisting.transact(xa)
          _ <- if (existing.isEmpty) {
            IO.unit
          } else {
            putStrLn("Overwrite the following rows?") >>
              existing.traverse(putStrLn) >> readLn
          }
          _ <- putStrLn(s"$insert")
          _ <- readLn
          affected <- insert.transact(xa)
        } yield affected

    }
  }

  def newRuns(configMap: Map[String, String],
              killProc: List[String] => Process[IO, _, _],
              launchRuns: IO[List[String]],
              insertNewRuns: List[String] => IO[Int])(implicit
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

  //  def lookupRuns(field: String, pattern: String)(
  //    implicit dbPath: Path): IO[ExitCode] = {
  //    for {
  //
  //      ids <- DB.connect(dbPath).use { db =>
  //        val query = table
  //          .filter(_.name like pattern)
  //          .map { row => row.name
  //            //            field match {
  //            //              case "commit" => row.commit
  //            //              case "config" => row.config
  //            //              case "configScript" => row.configScript
  //            //              case "containerId" => row.containerId
  //            //              case "description" => row.description
  //            //              case "killScript" => row.killScript
  //            //              case "launchScript" => row.launchScript
  //            //              case _ => row.name
  //            //            }
  //          }
  //        IO.fromFuture(IO(db.run(query.result)))
  //      }
  //      _ <- if (ids.isEmpty) {
  //        putStrLn(s"No runs found.")
  //      } else {
  //        ids.toList.traverse(putStrLn)
  //      }
  //    } yield ExitCode.Success
  //  }
  //
  //  def lsAllRuns(field: String)(
  //    implicit dbPath: Path): IO[ExitCode] = {
  //    for {
  //      ids <- DB.connect(dbPath).use { db =>
  //        val query = table
  //          .map(row => row.name)
  //        //          .map(_.stringToField(field))
  //        IO.fromFuture(IO(db.run(query.result)))
  //      }
  //      _ <- if (ids.isEmpty) {
  //        putStrLn(s"No runs found.")
  //      } else {
  //        ids.toList.traverse(putStrLn)
  //      }
  //    } yield ExitCode.Success
  //  }

  //  def reproduceRuns(pattern: String)(
  //    implicit dbPath: Path): IO[ExitCode] = {
  //    for {
  //      triples <- DB.connect(dbPath).use { db =>
  //        db.execute(
  //          table
  //            .filter(_.name like pattern)
  //            .map((e: RunTable) => {
  //              (e.commit, e.launchScript, e.config)
  //            }).result)
  //      }
  //      _ <- if (triples.isEmpty) {
  //        putStrLn(s"No runs match pattern $pattern")
  //      } else {
  //        triples.toList.traverse {
  //          case (commit, launchScript, config) =>
  //            putStrLn(s"git checkout $commit") >>
  //              putStrLn(s"""eval '$launchScript' $config""")
  //        }
  //      }
  //    } yield ExitCode.Success
  //  }

  //  def relaunchRuns(pattern: String, name: String, numRuns: Int,
  //                  )(
  //                    implicit dbPath: Path): IO[ExitCode] =
  //    for {
  //      configScripts <- DB.connect(dbPath).use { db =>
  //        db.execute(
  //          table
  //            .filter(_.name like pattern)
  //            .map(r => (r.configScript, r.launchScript, r.killScript)).result)
  //      }
  //
  //      _ <- Blocker[IO].use { blocker =>
  //        implicit val blocker: Blocker = blocker
  //        configScripts.iterator.collectFirst({ case (Some(configScript), launchScript, killScript) =>
  //          val configMap = ConfigMap.build(configScript = Paths.get(configScript),
  //            numRuns = numRuns, name = name)
  //          (configMap, launchScript, killScript)
  //        }) match {
  //          case None => IO.unit
  //          case Some((configMap, launchScript, killScript)) => {
  //            for {
  //              configMap <- configMap
  //              r <- newRuns(
  //              configMap = configMap,
  //              killProc = stringToProc(killScript),
  //              launchRuns = launchRuns(launchScript = launchScript, configMap=configMap)(blocker),
  //              inserNewRuns = insertNewRuns(
  //                launchProc, killScript
  //              ),
  //            )
  //            } yield r
  //          }
  //        }
  //      }
  //    } yield ExitCode.Success

  //      _ <- if (configScripts.isEmpty) {
  //        putStrLn(s"No runs match pattern $pattern")
  //      } else {
  //        for {
  //
  //        } yield
  //      }

  def readPath(path: Path): IO[String] =
    Resource
      .fromAutoCloseable(IO(scala.io.Source.fromFile(path.toFile)))
      .use((s: BufferedSource) => IO(s.mkString))

  def readMaybePath(path: Option[Path]): IO[Option[String]] = path match {
    case None    => IO.pure(None)
    case Some(p) => readPath(p) >>= (s => IO.pure(Some(s)))
  }

  //  def rmRuns(pattern: String, killProc: List[String] => Process[IO, _, _])(
  //    implicit dbPath: Path): IO[ExitCode] = {
  //    DB.connect(dbPath).use { db =>
  //      val query = table.filter(_.name like pattern)
  //      db.execute(query.result) >>= { (matches: Seq[RunRow]) =>
  //        val ids = matches.map(_.containerId).toList
  //        if (matches.isEmpty) {
  //          putStrLn(s"No runs match pattern $pattern")
  //        } else {
  //          putStrLn("Delete the following rows?") >>
  //            matches.map(_.name).toList.traverse(putStrLn) >>
  //            readLn >>
  //            Blocker[IO].use(killProc(ids).run(_)) >>
  //            db.execute(query.delete)
  //        }
  //      }
  //    } >> IO.pure(ExitCode.Success)
  //
  //  }
}

//object Main extends CommandApp {
//
//
////  override def run(args: List[String]): IO[ExitCode] = {
////    implicit val dbPath: Path = conf.dbPath()
////    return IO(ExitCode.Success)
//
//    conf.subcommand match {
//      case Some(conf.New) =>
//        val name = conf.New.name()
//        val launchScriptPath: Path = conf.New.launchScript()
//        Blocker[IO].use(b => {
//          implicit val blocker: Blocker = b
//          for {
//            configMap <- (conf.New.config.toOption,
//              conf.New.configScript.toOption,
//              conf.New.numRuns.toOption) match {
//              case (Some(config), _, None) =>
//                IO.pure(Map(name -> config))
//              case (None, Some(configScript), Some(numRuns)) =>
//                ConfigMap.build(configScript = configScript,
//                  numRuns = numRuns,
//                  name = name)
//              case _ =>
//                IO.raiseError(new RuntimeException(
//                  "--config and --num-runs are mutually exclusive argument groups."))
//            }
//            _ <- putStrLn("Create the following runs?") >>
//              configMap.print() >>
//              readLn
//            commit <- getCommit
//            description <- getDescription(conf.New.description.toOption)
//            configScript <- readMaybePath(conf.New.configScript.toOption)
//            launchScript <- readPath(launchScriptPath)
//            killScript <- readPath(conf.New.killScript())
//            result <- newRuns(
//              configMap = configMap,
//              killProc = killProc(conf.New.killScript()),
//              launchRuns = launchRuns(configMap, launchProc(launchScriptPath)),
//              insertNewRuns = insertNewRuns(
//                launchScript = launchScript,
//                killScript = killScript,
//                commit = commit,
//                description = description,
//                configScript = configScript,
//                configMap = configMap,
//              )
//            )
//          } yield result
//        })
//
//      //      case Some(conf.lookup) =>
//      //        lookupRuns(field = conf.lookup.field(), pattern = conf.lookup.pattern())
//      //      case Some(conf.rm) =>
//      //        rmRuns(killProc = killProc(conf.rm.killScript()),
//      //          pattern = conf.rm.pattern())
//      //      case Some(conf.ls) =>
//      //        lookupRuns(field = "name", pattern = conf.ls.pattern.toOption.getOrElse("%"))
//      //      case Some(conf.reproduce) => {
//      //        IO(ExitCode.Success)
//      //      }
//      case x => IO.raiseError(new RuntimeException(s"Don't know how to handle argument $x"))
//    }
//  }
//}
