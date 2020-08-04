package runs.manager

import java.sql.Timestamp
import java.time.Instant

import cats.data.NonEmptyList
import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, Clock, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import runs.manager.Main._

import scala.concurrent.duration.SECONDS

trait ReproduceCommand {

  def reproduceCommand(newName: Option[String],
                       pattern: String,
                       active: Boolean,
                       description: Option[String],
                       dockerRunBase: List[String],
                       containerVolume: String,
                       resample: Boolean,
                       interpreter: String,
                       interpreterArgs: List[String],
                       follow: Boolean,
  )(implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean): IO[ExitCode] = {
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
          val names = NonEmptyList(firstName, otherNames)
          for {
            existing <- findExisting(names)
            _ <- checkOverwrite(existing map (_.name)) >>
              findSharedVolumes(names) >>= {
              checkRmVolume(_)
            }
            configs <- oldRows.traverse { row =>
              (row.configScript, resample) match {
                case (Some(script), true) =>
                  sampleConfig(script, interpreter, interpreterArgs).map(
                    Some(_)
                  )
                case _ => IO.pure(row.config)
              }
            }
            now <- realTime
            newRows = (oldRows zip configs).map {
              case (row: RunRow, config: Option[String]) =>
                val name = newName.getOrElse(row.name)
                PartialRunRow(
                  commitHash = commitHash,
                  config = config,
                  configScript = row.configScript,
                  imageId = row.imageId,
                  description = description.getOrElse(row.description),
                  volume = name,
                  name = name,
                  datetime = now,
                )
            }
            existingVolumes <- existingVolumes(newRows.map(_.volume))
            _ <- initialDockerCommands(existing, existingVolumes) >> runThenInsert(
              partialRows = newRows,
              dockerRunBase = dockerRunBase,
              containerVolume = containerVolume,
              follow = follow,
            )
          } yield ()
      }
    } yield ExitCode.Success
  }
}
