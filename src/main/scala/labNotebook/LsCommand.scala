package labNotebook

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ContextShift, ExitCode, IO}
import cats.implicits._
import doobie.Fragments
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.ProcessRunner

import scala.language.postfixOps

trait LsCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val runner: ProcessRunner[IO]
  implicit val cs: ContextShift[IO]

  def selectConditions(pattern: String, active: Boolean)(
    implicit blocker: Blocker
  ): IO[Fragment]

  def lsCommand(
    pattern: String,
    active: Boolean
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[ExitCode] = {
    for {
      conditions <- selectConditions(pattern, active)
      names <- (fr"SELECT name FROM runs" ++ conditions)
        .query[String]
        .to[List]
        .transact(xa)
      _ <- names.traverse(putStrLn)
    } yield ExitCode.Success
  }
}
