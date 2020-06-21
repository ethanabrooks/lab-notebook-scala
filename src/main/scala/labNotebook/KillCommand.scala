package labNotebook

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ContextShift, ExitCode, IO}
import cats.implicits._
import doobie._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.{Process, ProcessRunner}

trait KillCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val runner: ProcessRunner[IO]
  implicit val cs: ContextShift[IO]
  def pause(implicit yes: Boolean): IO[Unit]
  def killProc(ids: List[String]): Process[IO, _, _]

  def selectConditions(pattern: Option[String], active: Boolean)(
    implicit blocker: Blocker
  ): IO[Fragment]

  def lookupNamesContainers(
    conditions: Fragment
  ): ConnectionIO[List[(String, String)]]

  def killCommand(pattern: Option[String])(implicit blocker: Blocker,
                                           xa: H2Transactor[IO],
                                           yes: Boolean): IO[ExitCode] = {
    for {
      conditions <- selectConditions(pattern, active = true)
      pairs <- lookupNamesContainers(conditions).transact(xa)
      containerIds <- pairs.unzip match {
        case (names, containerIds) =>
          putStrLn(
            if (yes) "Killing the following runs:"
            else "Kill the following runs?"
          ) >> names.traverse(putStrLn) >> pause >> IO.pure(containerIds)
      }
      _ <- killProc(containerIds).run(blocker)
    } yield ExitCode.Success
  }

}
