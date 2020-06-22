package labNotebook

import java.nio.file.{Path, Paths}

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ContextShift, ExitCode, IO}
import cats.implicits._
import doobie._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.{Process, ProcessRunner}
import labNotebook.Main.essentialDataQuery

import scala.language.postfixOps

trait RmCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val runner: ProcessRunner[IO]
  implicit val cs: ContextShift[IO]

  def killProc(ids: List[String]): Process[IO, _, _]
  def selectConditions(pattern: Option[String], active: Boolean)(
    implicit blocker: Blocker
  ): IO[Fragment]
  def rmStatement(names: List[String]): ConnectionIO[_]
  def recursiveRemove(path: Path)(implicit blocker: Blocker): IO[List[Unit]]
  def killContainers(containers: List[String])(
    implicit blocker: Blocker
  ): IO[Unit]

  def getEssentialDataResult(
    conditions: Fragment
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[List[EssentialRunData]]

  def rmCommand(
    pattern: Option[String],
    active: Boolean
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[ExitCode] = {
    if (!active && pattern.isEmpty)
      putStrLn("This will delete all runs. Are you sure?") >> readLn
    else IO.unit
    for {
      conditions <- selectConditions(pattern, active)
      results <- getEssentialDataResult(conditions)
      _ <- {
        val names = results.map(_.name)
        val containerIds = results.map(_.containerId)
        val logDirs = results.map(_.logDir)
        putStrLn("Remove the following runs?") >>
          names.traverse(putStrLn) >> readLn >>
          killContainers(containerIds) >>
          rmStatement(names).transact(xa) >>
          putStrLn("Removing created direckories...") >>
          logDirs.traverse(d => putStrLn(d.toString) >> recursiveRemove(d))
      }
    } yield ExitCode.Success
  }
}
