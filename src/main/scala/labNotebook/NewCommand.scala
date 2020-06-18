package labNotebook

import java.nio.file.Path

import cats.Monad
import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, Concurrent, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import doobie._
import Fragments.in
import doobie.h2._
import doobie.implicits._
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImpl
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}

import scala.io.BufferedSource
import scala.language.postfixOps

trait NewCommand {
  private implicit val cs: ContextShift[IO] =
    IO.contextShift(ExecutionContexts.synchronous)
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
      Monad[IO].replicateA(numRuns, configProc.run(blocker)) >>= { results =>
        val pairs =
          for ((result, i) <- results.zipWithIndex)
            yield (s"$name$i", result.output)
        IO.pure(pairs.toMap)
      }
    }
  }

  def transactor(implicit dbPath: Path): Resource[IO, H2Transactor[IO]] = {
    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](32) // our connect EC
      be <- Blocker[IO] // our blocking EC
      xa <- H2Transactor.newH2Transactor[IO](
        s"jdbc:h2:$dbPath;DB_CLOSE_DELAY=-1", // connect URL
        "sa", // username
        "", // password
        ce, // await connection here
        be // execute JDBC operations here
      )
    } yield xa
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

  def killProc(script: Path)(ids: List[String]): Process[IO, _, _] =
    Process[IO](script.toString, ids)

  def launchProc(script: Path)(config: String): ProcessImpl[IO] =
    Process[IO]("bash", script.toString :: List(config))

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

  def readPath(path: Path): IO[String] =
    Resource
      .fromAutoCloseable(IO(scala.io.Source.fromFile(path.toFile)))
      .use((s: BufferedSource) => IO(s.mkString))

  def readConfigScript(newMethod: NewMethod): IO[Option[String]] =
    newMethod match {
      case FromConfig(_)          => IO.pure(None)
      case FromConfigScript(p, _) => readPath(p) >>= (s => IO.pure(Some(s)))
    }

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
      Run(
        checkpoint = None,
        commitHash = commit,
        config = config,
        configScript = configScript,
        containerId = id,
        description = description,
        events = None,
        killScript = killScript,
        launchScript = launchScript,
        name = name,
      )
    configMap.keys.toList.toNel match {
      case None => IO.raiseError(new RuntimeException("Empty configMap"))
      case Some(names) =>
        val drop = sql"DROP TABLE IF EXISTS runs".update.run
        val create = Run.createTable.update.run
        val checkExisting =
          (fr"SELECT name FROM runs WHERE" ++ in(fr"name", names))
            .query[String]
            .to[List]
        val placeholders = Run.fields.map(_ => "?").mkString(",")
        val insert: doobie.ConnectionIO[Int] = Update[Run](
          s"MERGE INTO runs KEY (name) values ($placeholders)"
        ).updateMany(newRows)
        val ls =
          sql"SELECT name FROM runs"
            .query[String]
            .to[List]
        transactor.use { xa =>
          for {
            _ <- drop.transact(xa) //TODO
            existing <- (create, checkExisting)
              .mapN((_, e) => e)
              .transact(xa)
            affected <- {
              if (existing.isEmpty) { IO.unit } else {
                putStrLn("Overwrite the following rows?") >>
                  existing.traverse(putStrLn) >>
                  readLn
              }
            } >> insert.transact(xa)
            _ <- ls.transact(xa) >>= (_ traverse putStrLn) //TODO
          } yield affected
        }

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
          putStrLn("Placeholder text for killing.")
        //          killProc(containerIds).run(blocker).void TODO
      } as ExitCode.Success
  }

  def newCommand(name: String,
                 description: Option[String],
                 launchScriptPath: Path,
                 killScriptPath: Path,
                 newMethod: NewMethod)(implicit dbPath: Path): IO[ExitCode] = {
    Blocker[IO].use(b => {
      implicit val blocker: Blocker = b
      for {
        configMap <- newMethod match {
          case FromConfig(config) =>
            IO.pure(Map(name -> config))
          case FromConfigScript(configScript, numRuns) =>
            ConfigMap.build(
              configScript = configScript,
              numRuns = numRuns,
              name = name
            )
        }
        _ <- putStrLn("Create the following runs?") >>
          configMap.print() >>
          readLn
        commit <- getCommit
        description <- getDescription(description)
        configScript <- readConfigScript(newMethod)
        launchScript <- readPath(launchScriptPath)
        killScript <- readPath(killScriptPath)
        _ <- putStrLn(s"configMap: $configMap")
        _ <- putStrLn(s"killScript: $killScript")
        _ <- putStrLn(s"launchScript: $launchScript")
        result <- newRuns(
          configMap = configMap,
          killProc = killProc(killScriptPath),
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
  }
}
