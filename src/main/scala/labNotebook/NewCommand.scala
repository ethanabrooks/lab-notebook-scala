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

trait NewCommand {
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
  def newCommand(name: String,
                 description: Option[String],
                 launchScript: Path,
                 killScript: Path,
                 newMethod: NewMethod): IO[ExitCode] = {
    IO(ExitCode.Success)
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

  def readPath(path: Path): IO[String] =
    Resource
      .fromAutoCloseable(IO(scala.io.Source.fromFile(path.toFile)))
      .use((s: BufferedSource) => IO(s.mkString))

  def readMaybePath(path: Option[Path]): IO[Option[String]] = path match {
    case None    => IO.pure(None)
    case Some(p) => readPath(p) >>= (s => IO.pure(Some(s)))
  }
}
