package labNotebook

import java.nio.file.Path

import cats.effect
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.{Process, ProcessRunner}

import cats.Monad
import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import doobie._
import Fragments.in
import doobie.h2.H2Transactor
import doobie.implicits._
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImpl
import io.github.vigoo.prox.{Process, ProcessRunner}

import scala.io.BufferedSource
import scala.language.postfixOps

import scala.language.postfixOps

trait KillCommand {
  private val captureOutput: Pipe[IO, Byte, String] = fs2.text.utf8Decode[IO]
  implicit val cs: ContextShift[IO]
  implicit val runner: ProcessRunner[IO]

  def transactor(implicit dbPath: Path,
                 blocker: Blocker): Resource[IO, H2Transactor[IO]]

  def killProc(ids: List[String]): Process[IO, _, _]

  def killCommand(pattern: String)(implicit dbPath: Path): IO[ExitCode] =
    Blocker[IO].use(b => {
      implicit val blocker: Blocker = b
      transactor.use { xa =>
        (fr"SELECT (containerId) FROM runs WHERE name LIKE" ++ Fragment
          .const(pattern))
          .query[String]
          .to[List]
          .transact(xa)
      } >>= { ids =>
        putStrLn("Kill the following runs?" + ids) >> readLn >> IO.pure(ids)
      } >>= { killProc(_).run(blocker) }
    } as ExitCode.Success)
}
