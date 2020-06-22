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
  def getCommit(implicit blocker: Blocker): IO[String]
  def newDirectories(logDir: Path, num: Int)(
    implicit blocker: Blocker
  ): IO[List[Path]]
  def findExisting(names: NonEmptyList[String])(
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
  def existingLogDir(name: String, existing: List[Existing]): Option[Path]

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
             logDir: Path,
             containerId: String): RunRow = {
    RunRow(
      commitHash = commitHash,
      config = oldRow.config,
      configScript = oldRow.configScript,
      containerId = containerId,
      imageId = oldRow.imageId,
      description = description.getOrElse(oldRow.description),
      logDir = logDir.toString,
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
      names = oldRows.zipWithIndex.map {
        case (row, i) =>
          newName match {
            case Some(name) => s"$name$i"
            case None       => row.name
          }
      }
      _ <- names match {
        case Nil => putStrLn("No matching runs found.")
        case firstName :: otherNames =>
          for {
            existing <- findExisting(NonEmptyList(firstName, otherNames))
            _ <- checkOverwrite(existing.map(_.name))
            existingLogDirs = names.map(existingLogDir(_, existing))
            newLogDirs <- newDirectories(logDir, names.length)
            logDirs = (existingLogDirs zip newLogDirs).map {
              case (existingLogDir, newLogDir) =>
                existingLogDir.getOrElse(newLogDir)
            }
            ops: List[Ops] = (oldRows zip logDirs).map {
              case (r: RunRow, logDir: Path) =>
                createOps(image = r.imageId, config = r.config, path = logDir)
            }
            _ <- createBrackets(
              newRows = _.zip(oldRows zip logDirs).map {
                case (containerId, (oldRow, logDir)) =>
                  newRow(
                    oldRow = oldRow,
                    newName = newName,
                    commitHash = commitHash,
                    description = description,
                    containerId = containerId,
                    logDir = logDir
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
