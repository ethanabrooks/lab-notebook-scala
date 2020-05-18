package labNotebook

import java.nio.file.{Path, Paths}

import cats.Monad
import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, Concurrent, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImpl
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}
import labNotebook.Main.{DB, insertNewRuns, newRuns}
import slick.jdbc.H2Profile
import slick.jdbc.H2Profile.api._
import slick.lifted.TableQuery

import scala.io.BufferedSource
import scala.language.postfixOps

object Main extends IOApp {
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
    type DatabaseDef = H2Profile.backend.DatabaseDef

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
      case None => getCommitMessage(blocker)
    }
  }

  def launchProc(script: Path)(config: String): ProcessImpl[IO] = {
    Process[IO](script.toString, List(config))
  }

  def killProc(script: Path)(ids: List[String]): Process[IO, _, _] = {
    Process[IO](script.toString, ids)
  }


  def launchRuns(configMap: Map[String, String], launchProc: String => ProcessImpl[IO])(
    implicit blocker: Blocker): IO[List[String]] =
    for {
      fibers <- configMap.values.toList.traverse { config =>
        val proc: Process[IO, String, _] = launchProc(config) ># captureOutput
        Concurrent[IO].start(proc.run(blocker))
      }
      results <- fibers
        .map(_.join)
        .traverse(_ >>= (r => IO.pure(r.output)))
    } yield results

  def insertNewRuns(launchProc: String => Process[IO, _, _],
                    killScript: Process[IO, _, _],
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
        checkpoint = None,
        commit = commit,
        config = config,
        configScript = configScript,
        containerId = id,
        description = description,
        events = None,
        killScript = killScript.toString,
        launchScript = launchProc(config).toString,
        name = name,
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
              killProc: List[String] => Process[IO, _, _],
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
        val query = table
          .filter(_.name like pattern)
          .map { row => row.name
            //            field match {
            //              case "commit" => row.commit
            //              case "config" => row.config
            //              case "configScript" => row.configScript
            //              case "containerId" => row.containerId
            //              case "description" => row.description
            //              case "killScript" => row.killScript
            //              case "launchScript" => row.launchScript
            //              case _ => row.name
            //            }
          }
        IO.fromFuture(IO(db.run(query.result)))
      }
      _ <- if (ids.isEmpty) {
        putStrLn(s"No runs found.")
      } else {
        ids.toList.traverse(putStrLn)
      }
    } yield ExitCode.Success
  }

  def lsAllRuns(field: String)(
    implicit dbPath: Path): IO[ExitCode] = {
    for {
      ids <- DB.connect(dbPath).use { db =>
        val query = table
          .map(row => row.name)
        //          .map(_.stringToField(field))
        IO.fromFuture(IO(db.run(query.result)))
      }
      _ <- if (ids.isEmpty) {
        putStrLn(s"No runs found.")
      } else {
        ids.toList.traverse(putStrLn)
      }
    } yield ExitCode.Success
  }

  def reproduceRuns(pattern: String)(
    implicit dbPath: Path): IO[ExitCode] = {
    for {
      triples <- DB.connect(dbPath).use { db =>
        db.execute(
          table
            .filter(_.name like pattern)
            .map((e: RunTable) => {
              (e.commit, e.launchScript, e.config)
            }).result)
      }
      _ <- if (triples.isEmpty) {
        putStrLn(s"No runs match pattern $pattern")
      } else {
        triples.toList.traverse {
          case (commit, launchScript, config) =>
            putStrLn(s"git checkout $commit") >>
              putStrLn(s"""eval '$launchScript' $config""")
        }
      }
    } yield ExitCode.Success
  }

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


  def readConfigScript(path: Option[Path]): IO[Option[String]] = path match {
    case None => IO.pure(None)
    case Some(p) =>
      Resource
        .fromAutoCloseable(IO(scala.io.Source.fromFile(p.toFile)))
        .use((s: BufferedSource) => IO(Some(s.mkString)))

  }

  def rmRuns(pattern: String, killProc: List[String] => Process[IO, _, _])(
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
              launchRuns = launchRuns(configMap, launchProc(launchScript)),
              insertNewRuns = insertNewRuns(
                launchProc = launchProc(launchScript),
                killScript = killProc(conf.New.killScript())(List()),  // TODO
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
      case Some(conf.ls) =>
        lookupRuns(field = "name", pattern = conf.ls.pattern.toOption.getOrElse("%"))
      case Some(conf.reproduce) => {
        IO(ExitCode.Success)
      }
      case x => IO.raiseError(new RuntimeException(s"Don't know how to handle argument $x"))
    }
  }
}
