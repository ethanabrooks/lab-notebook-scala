package runs.manager

import java.nio.file.Path

import cats.Monad
import cats.data.NonEmptyList
import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import runs.manager.Main._

case class UpdatedData(name: Option[String],
                       containerId: String,
                       commitHash: String,
                       description: String,
                       logDir: Path,
                       config: String)

trait ReproduceCommand {

  def reproduceCommand(newName: Option[String],
                       pattern: String,
                       active: Boolean,
                       description: Option[String],
                       dockerRunBase: List[String],
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
            configs <- oldRows.traverse { row =>
              (row.configScript, resample) match {
                case (Some(script), true) =>
                  sampleConfig(script, interpreter, interpreterArgs)
                case _ => IO.pure(row.config)
              }
            }
            newRows = (oldRows zip configs).map {
              case (row, config) =>
                val name = newName.getOrElse(row.name)
                RunRow(
                  commitHash = commitHash,
                  config = config,
                  configScript = row.configScript,
                  containerId = "PLACEHOLDER",
                  imageId = row.imageId,
                  description = description.getOrElse(row.description),
                  volume = name,
                  name = name
                )
            }
            _ <- runThenInsert(
              newRows = _.zip(newRows).map {
                case (containerId, newRow) =>
                  newRow.copy(containerId = containerId)
              },
              launchDocker = newRows.traverse(
                row =>
                  runDocker(
                    dockerRunBase = dockerRunBase,
                    volume = row.volume,
                    image = row.imageId,
                    config = row.config
                )
              ),
              existing = existing.map(
                (e: Existing) => DockerPair(e.container, e.volume)
              ),
            )
          } yield ()
      }
    } yield ExitCode.Success

}
