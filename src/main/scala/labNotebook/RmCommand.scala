package labNotebook

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import doobie._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.{Process, ProcessRunner}

import scala.language.postfixOps

trait RmCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val runner: ProcessRunner[IO]
  implicit val cs: ContextShift[IO]

  def killProc(ids: List[String]): Process[IO, _, _]

  def rmCommand(
    pattern: String,
    active: Boolean
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[ExitCode] = {
    IO(ExitCode.Error) //TODO
  }
}
