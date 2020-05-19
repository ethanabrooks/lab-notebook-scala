package labNotebook

import java.nio.file.Path
import doobie._
import Fragments.in
import cats.Monad
import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, Concurrent, ContextShift, ExitCode, IO, IOApp, Resource}
import cats.implicits._
import doobie.implicits._
import doobie.util.yolo
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImpl
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}

import scala.io.BufferedSource
import scala.language.postfixOps

object Main extends IOApp {
  private implicit val cs: ContextShift[IO] = IO.contextShift(ExecutionContexts.synchronous)
  private val xa = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = "jdbc:postgresql:runs",
    user = "postgres",
    pass = "",
    Blocker.liftExecutionContext(ExecutionContexts.synchronous) // just for testing
  )
  private val y: yolo.Yolo[IO] = xa.yolo

  import y._

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

  def insertNewRuns(launchScript: String,
                    killScript: String,
                    commit: String,
                    description: String,
                    configScript: Option[String],
                    configMap: Map[String, String],
                   )(containerIds: List[String])(
                     implicit dbPath: Path,
                   ): IO[Int] = {
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
        val checkExisting = (fr"SELECT name FROM runs WHERE" ++ in(fr"name", names))
          .query[String].to[List]
        val fields = RunRow.fields.mkString(",")
        val placeholders = RunRow.fields.map(_ => "?").mkString(",")
        val sets = for {
          field <- RunRow.fields
        } yield fr"$field = table.$field"
        val selects = for {
          (field, i) <- RunRow.fields.zipWithIndex
          column = for (r <- newRows) yield r.productElement(i)
        } yield fr"SELECT unnest(array[${column.mkString(",")}]) AS $field"
        val upserts: doobie.ConnectionIO[Int] =
          Update[RunRow](
          s"insert into runs ($fields) values ($placeholders)"
            ).updateMany(newRows)
        //               ON CONFLICT (name)
        //               UPDATE runs set ${sets.mkString(",")}
        //               from
        //               (${selects.mkString(",")}) as tmp
        //               where runs.name = tmp.name;
        for {
          existing <- checkExisting.transact(xa)
          _ <- if (existing.isEmpty) {
            IO.unit
          } else {
            putStrLn("Overwrite the following rows?") >>
              existing.traverse(putStrLn)
            //            >> readLn
          }
          _ <- putStrLn(upserts.toString())
          _ <- readLn
          affected <- upserts.transact(xa)
        } yield affected

    }
    //    val f3 = codes.toNel.map(cs => in(fr"code", cs))
    //    val checkExisting =
    //      table
    //        .filter(_.name inSet configMap.keys)
    //        .map(_.name)
    //        .result
    //    val upserts = for (row <- newRows) yield table.insertOrUpdate(row)

    //    DB.connect(dbPath).use { db =>
    //      for {
    //        existing <- db.execute(checkExisting)
    //        checkIfExisting = if (existing.isEmpty) {
    //          IO.unit
    //        } else {
    //          putStrLn("Overwrite the following rows?") >>
    //            existing.toList.traverse(putStrLn) >>
    //            readLn
    //        }
    //        r <- checkIfExisting >> db.execute(DBIO.sequence(upserts))
    //      } yield r
    //    }
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
    case None => IO.pure(None)
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

  override def run(args: List[String]): IO[ExitCode] = {
    val conf = new Conf(args)
    implicit val dbPath: Path = conf.dbPath()

    conf.subcommand match {
      case Some(conf.New) =>
        val name = conf.New.name()
        val launchScriptPath: Path = conf.New.launchScript()
        Blocker[IO].use(b => {
          implicit val blocker: Blocker = b
          for {
            configMap <- (conf.New.config.toOption,
              conf.New.configScript.toOption,
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
            configScript <- readMaybePath(conf.New.configScript.toOption)
            launchScript <- readPath(launchScriptPath)
            killScript <- readPath(conf.New.killScript())
            result <- newRuns(
              configMap = configMap,
              killProc = killProc(conf.New.killScript()),
              launchRuns = launchRuns(configMap, launchProc(launchScriptPath)),
              insertNewRuns = insertNewRuns(
                launchScript = launchScript,
                killScript = killScript,
                commit = commit,
                description = description,
                configScript = configScript,
                configMap = configMap,
              )
            )
          } yield result
        })

      //      case Some(conf.lookup) =>
      //        lookupRuns(field = conf.lookup.field(), pattern = conf.lookup.pattern())
      //      case Some(conf.rm) =>
      //        rmRuns(killProc = killProc(conf.rm.killScript()),
      //          pattern = conf.rm.pattern())
      //      case Some(conf.ls) =>
      //        lookupRuns(field = "name", pattern = conf.ls.pattern.toOption.getOrElse("%"))
      //      case Some(conf.reproduce) => {
      //        IO(ExitCode.Success)
      //      }
      case x => IO.raiseError(new RuntimeException(s"Don't know how to handle argument $x"))
    }
  }
}
