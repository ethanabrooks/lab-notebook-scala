package labNotebook

import java.nio.file.{Path, Paths}

import cats.Monad
import cats.effect.Console.io.putStrLn
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, ContextShift, ExitCode, IO}
import cats.implicits._
import doobie._
import Fragments.in
import cats.data.NonEmptyList
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.io.file._
import fs2.{Pipe, text}
import io.github.vigoo.prox.Process.{ProcessImpl, ProcessImplO}
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

  def selectConditions(pattern: Option[String], active: Boolean)(
    implicit blocker: Blocker
  ): IO[Fragment]
  def createRuns(
    configMap: Map[String, String],
    description: Option[String],
    configScript: Option[String],
    image: String,
    imageId: String,
    logDir: Path
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit]

  def reproduceCommand(pattern: String,
                       active: Boolean,
                       description: Option[String],
                       logDir: Path,
  )(implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean): IO[ExitCode] = {
    for {
      conditions <- selectConditions(Some(pattern), active)
      configPairs <- (fr"SELECT (imageId, config, description) FROM runs" ++ conditions)
        .query[(String, String)]
        .to[List]
        .transact(xa)
      (imageIds, configs) = configPairs.unzip
      configMap = configPairs.toMap
      _ <- createRuns(
        configMap = configMap,
        configScript = None,
        description = description,
        image = ???,
        imageId = ???,
        logDir = logDir,
      )

    } yield ExitCode.Success

  }

}
