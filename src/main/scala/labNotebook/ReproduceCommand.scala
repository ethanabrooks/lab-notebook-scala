package labNotebook

import java.nio.file.Path

import cats.Monad
import cats.data.NonEmptyList
import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import labNotebook.Main._

case class UpdatedData(name: Option[String],
                       containerId: String,
                       commitHash: String,
                       description: String,
                       logDir: Path,
                       config: String)

trait ReproduceCommand {
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
                       resample: Boolean,
                       interpreter: String,
                       interpreterArgs: List[String],
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
            newRows <- (oldRows zip logDirs).traverse {
              case (row, dir) =>
                for {
                  config <- (row.configScript, resample) match {
                    case (Some(script), true) =>
                      sampleConfig(script, interpreter, interpreterArgs)
                    case _ => IO.pure(row.config)
                  }
                } yield
                  (containerId: String) =>
                    RunRow(
                      commitHash = commitHash,
                      config = config,
                      configScript = row.configScript,
                      containerId = containerId,
                      imageId = row.imageId,
                      description = description.getOrElse(row.description),
                      logDir = dir.toString,
                      name = newName.getOrElse(row.name)
                  )
            }
            _ <- createBrackets(
              newRows = _.zip(newRows).map {
                case (containerId, newRow) =>
                  newRow(containerId)
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
