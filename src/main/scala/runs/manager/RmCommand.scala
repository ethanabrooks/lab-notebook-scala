package runs.manager

import java.nio.file.Paths

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import runs.manager.Main._

import scala.language.postfixOps

trait RmCommand {
  def rmCommand(
    pattern: Option[String],
    active: Boolean
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[ExitCode] = {
    if (!active && pattern.isEmpty)
      putStrLn("This will delete all runs. Are you sure?") >> readLn
    else IO.unit
    for {
      conditions <- selectConditions(pattern, active)
      results <- essentialDataQuery(conditions).transact(xa)
      _ <- {
        val names = results.map(_.name)
        val containerIds = results.map(_.containerId)
        val logDirs = results.map(_.logDir)
        putStrLn("Remove the following runs?") >>
          names.traverse(putStrLn) >> readLn >>
          killContainers(containerIds) >>
          rmStatement(names).transact(xa) >>
          putStrLn("Removing created direckories...") >>
          logDirs.traverse(d => putStrLn(d) >> recursiveRemove(Paths.get(d)))
      }
    } yield ExitCode.Success
  }
}
