package labNotebook

import java.nio.file.{Path, Paths}

import cats.data.NonEmptyList
import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, ContextShift, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImplO
import io.github.vigoo.prox.{Process, ProcessRunner}

trait ReproduceCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val cs: ContextShift[IO]
  implicit val runner: ProcessRunner[IO]
  def killProc(ids: List[String]): Process[IO, _, _]
  def dockerPsProc: ProcessImplO[IO, String]
  def activeContainers(implicit blocker: Blocker): IO[List[String]]
  def recursiveRemove(path: Path)(implicit blocker: Blocker): IO[List[Unit]]
  def pause(implicit yes: Boolean): IO[Unit]
  def killContainers(containers: List[String])(
    implicit blocker: Blocker
  ): IO[Unit]
  def getCommit(implicit blocker: Blocker): IO[String]
  def newDirectories(logDir: Path, num: Int)(
    implicit blocker: Blocker
  ): IO[List[Path]]
  def lookupExisting(names: NonEmptyList[String])(
    implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean
  ): IO[List[Existing]]
  def checkOverwrite(
    existing: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit]
  def createBrackets(
    newRows: List[String] => List[RunRow],
    directoryMoves: IO[List[Option[PathMove]]],
    newDirectories: IO[List[Path]],
    containerIds: IO[List[String]],
    existingContainers: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[Unit]

  def selectConditions(pattern: Option[String], active: Boolean)(
    implicit blocker: Blocker
  ): IO[Fragment]

  def createOps(image: String, config: String, path: Path)(
    implicit blocker: Blocker
  ): Ops

  def newRow(oldRow: RunRow,
             newName: Option[String],
             commitHash: String,
             description: Option[String],
             containerId: String): RunRow = {
    RunRow(
      commitHash = commitHash,
      config = oldRow.config,
      configScript = oldRow.configScript,
      containerId = containerId,
      imageId = oldRow.imageId,
      description = description.getOrElse(oldRow.description),
      logDir = oldRow.logDir,
      name = newName.getOrElse(oldRow.name)
    )
  }

  def reproduceCommand(newName: Option[String],
                       pattern: String,
                       active: Boolean,
                       description: Option[String],
                       logDir: Path,
  )(implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean): IO[ExitCode] =
    for {
      conditions <- selectConditions(Some(pattern), active)
      oldRows <- (fr"SELECT * FROM runs" ++ conditions)
        .query[RunRow]
        .to[List]
        .transact(xa)
      commitHash <- getCommit
      _ <- oldRows.map(_.name) match {
        case Nil => putStrLn("No matching runs found.")
        case firstName :: otherNames =>
          for {
            existing <- lookupExisting(NonEmptyList(firstName, otherNames))
            _ <- checkOverwrite(existing.map(_.name))
            logDirs <- newDirectories(logDir, existing.length)
            ops: List[Ops] = (oldRows zip logDirs).map {
              case (r: RunRow, logDir: Path) =>
                createOps(image = r.imageId, config = r.config, path = logDir)
            }
            _ <- createBrackets(
              newRows = _.zip(oldRows).map {
                case (containerId, oldRow) =>
                  newRow(
                    oldRow = oldRow,
                    newName = newName,
                    commitHash = commitHash,
                    description = description,
                    containerId = containerId
                  )
              },
              directoryMoves = ops.traverse(_.moveDir),
              newDirectories = ops.traverse(_.createDir),
              containerIds = ops.traverse(_.launchRuns),
              existingContainers = existing.map(_.container),
            )
          } yield ()
      }
    } yield ExitCode.Success

}
