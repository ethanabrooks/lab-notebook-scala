package labNotebook

import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import doobie.{ConnectionIO, ExecutionContexts, Fragments}
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}

import scala.language.postfixOps

object Main
    extends CommandIOApp(
      name = "run-manager",
      header = "Manages long-running processes (runs).",
    )
    with MainOpts
    with NewCommand
    with LsCommand
    with RmCommand
    with KillCommand {

  val captureOutput: Pipe[IO, Byte, String] = fs2.text.utf8Decode[IO]
  implicit val cs: ContextShift[IO] =
    IO.contextShift(ExecutionContexts.synchronous)
  implicit val runner: ProcessRunner[IO] = new JVMProcessRunner

  def selectConditions(pattern: String, active: Boolean)(
    implicit blocker: Blocker
  ): IO[Array[Fragment]] = {
    val nameLikePattern = fr"name LIKE" ++ Fragment.const(s"'$pattern'")
    val ps = Process[IO]("docker", List("ps", "-q")) ># captureOutput
    if (active)
      ps.run(blocker).map { activeIds =>
        activeIds.output
          .split("\n")
          .map(_.stripLineEnd)
          .map(
            id =>
              nameLikePattern ++
                fr"AND containerId LIKE" ++ Fragment
                .const(s"'$id%'")
          ) // TODO: remove const
      } else
      IO.pure {
        Array(nameLikePattern)
      }
  }

  def rmStatement(names: List[String]): ConnectionIO[_] = {
    val conditions = names.map(name => fr"name =" ++ Fragment.const(s"'$name"))
    val statement = fr"DELETE * FROM runs where" ++ Fragments
      .or(conditions.toIndexedSeq: _*)
    statement.update.run
  }

  def lookupNamesContainers(
    conditions: Array[Fragment]
  ): ConnectionIO[List[(String, String)]] = {
    (fr"SELECT name, containerId FROM runs WHERE" ++
      Fragments.or(conditions.toIndexedSeq: _*))
      .query[(String, String)]
      .to[List]
  }

  def killProc(ids: List[String]): Process[IO, _, _] =
    Process[IO]("docker", "kill" :: ids)

  override def main: Opts[IO[ExitCode]] = opts.map {
    case AllOpts(dbPath, server, logDir, sub) =>
      val uri: String =
        "jdbc:h2:%s%s;DB_CLOSE_DELAY=-1".format(if (server) {
          s"tcp://localhost/"
        } else {
          ""
        }, dbPath);

      Blocker[IO].use { b =>
        implicit val blocker: Blocker = b
        val transactor: Resource[IO, H2Transactor[IO]] = for {
          ce <- ExecutionContexts.fixedThreadPool[IO](32) // our connect EC
          xa <- H2Transactor.newH2Transactor[IO](
            uri, // connect URL
            "sa", // username
            "", // password
            ce, // await connection here
            blocker // execute JDBC operations here
          )
        } yield xa
        transactor.use { x =>
          implicit val xa: H2Transactor[IO] = x
          sub match {
            case New(
                name,
                description,
                image,
                imageBuildPath,
                dockerfilePath,
                newMethod: NewMethod
                ) =>
              newCommand(
                name = name,
                description = description,
                logDir = logDir,
                image = image,
                imageBuildPath,
                dockerfilePath,
                newMethod = newMethod
              )
            case LsOpts(pattern, active) => lsCommand(pattern, active)
            case RmOpts(pattern, active) => rmCommand(pattern, active)
            case KillOpts(pattern) =>
              killCommand(pattern)
          }
        }
      }
  }
}
