package runs.manager

import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import runs.manager.Main._

trait KillCommand {
  def killCommand(pattern: Option[String], active: Boolean)(
    implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean
  ): IO[ExitCode] = {
    for {
      conditions <- selectConditions(pattern, active)
      results <- essentialDataQuery(conditions).transact(xa)
      containerIds <- {
        putStrLn(
          if (yes) "Killing the following runs:"
          else "Kill the following runs?"
        ) >>
          results.map(_.name).traverse(putStrLn) >>
          pause >>
          IO.pure(results.map(_.containerId))
      }
      _ <- killProc(containerIds).run(blocker)
    } yield ExitCode.Success
  }

}
