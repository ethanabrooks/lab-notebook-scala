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

  def killProc(ids: List[String]): Process[IO, _, _]

  def selectConditions(pattern: String, active: Boolean)(
    implicit blocker: Blocker
  ): IO[Array[Fragment]]

  def lookupNamesContainers(
    conditions: Array[Fragment]
  ): ConnectionIO[List[(String, String)]]

  def killCommand(pattern: String)(implicit blocker: Blocker,
                                   xa: H2Transactor[IO]): IO[ExitCode] = {
    for {
      conditions <- selectConditions(pattern, active = true)
      pairs <- lookupNamesContainers(conditions).transact(xa)
      containerIds <- pairs.unzip match {
        case (names, containerIds) =>
          putStrLn("Kill the following runs?") >> names
            .traverse(putStrLn) >> readLn >>
            IO.pure(containerIds)
      }
      _ <- killProc(containerIds).run(blocker)
    } yield ExitCode.Success
  }

}
