package labNotebook

import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import doobie.h2.H2Transactor
import fs2.Pipe
import io.github.vigoo.prox.{Process, ProcessRunner}

import scala.language.postfixOps

trait LsCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val runner: ProcessRunner[IO]
  implicit val cs: ContextShift[IO]

  def lsCommand(
    pattern: String,
    active: Boolean
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[ExitCode] = {
    IO(ExitCode.Error) //TODO
  }
}
