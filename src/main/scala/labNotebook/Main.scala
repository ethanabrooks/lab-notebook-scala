package labNotebook

import cats.effect.Console.io.readLn
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.{ConnectionIO, ExecutionContexts, Fragments}
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
  def pause(implicit yes: Boolean): IO[Unit] = if (yes) IO.unit else readLn.void

  def selectConditions(pattern: Option[String], active: Boolean)(
    implicit blocker: Blocker
  ): IO[Fragment] = {
    val nameLikePattern: Option[Fragment] =
      pattern.map(p => fr"AND name LIKE $p")
    if (active)
      (Process[IO]("docker", List("ps", "-q")) ># captureOutput)
        .run(blocker)
        .map(activeIds => {
          val fragments: Array[Fragment] = activeIds.output
            .split("\n")
            .map(_.stripLineEnd)
            .map(id => {
              val condition = fr"containerId LIKE ${id + "%"}"
              nameLikePattern.fold(condition)(condition ++ _)
            })
          val orClauses = Fragments.or(fragments.toIndexedSeq: _*)
          fr"WHERE" ++ orClauses
        })
    else {
      IO.pure(nameLikePattern.fold(fr"WHERE")(fr"WHERE" ++ _))
    }
  }

  def rmStatement(names: List[String]): ConnectionIO[_] = {
    val conditions = names.map(name => fr"name =" ++ Fragment.const(s"'$name"))
    val statement = fr"DELETE * FROM runs where" ++ Fragments
      .or(conditions.toIndexedSeq: _*)
    statement.update.run
  }

  def lookupNamesContainers(
    conditions: Fragment
  ): ConnectionIO[List[(String, String)]] = {
    (fr"SELECT name, containerId FROM runs" ++ conditions)
      .query[(String, String)]
      .to[List]
  }

  def killProc(ids: List[String]): Process[IO, _, _] =
    Process[IO]("docker", "kill" :: ids)

  override def main: Opts[IO[ExitCode]] = opts.map {
    case AllOpts(dbPath, server, y, logDir, sub) =>
      val uri: String =
        "jdbc:h2:%s%s;DB_CLOSE_DELAY=-1".format(if (server) {
          s"tcp://localhost/"
        } else {
          ""
        }, dbPath);

      implicit val yes: Boolean = y;
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
                imageBuildPath = imageBuildPath,
                dockerfilePath = dockerfilePath,
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
