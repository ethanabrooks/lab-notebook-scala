package runs.manager

import java.nio.file.{Path, Paths}

import cats.Monad
import cats.effect.Console.io.putStrLn
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie._
import Fragments.in
import cats.data.NonEmptyList
import doobie.h2.H2Transactor
import doobie.implicits._
import fs2.io.file._
import fs2.text
import io.github.vigoo.prox.Process
import io.github.vigoo.prox.Process.{ProcessImpl, ProcessImplO}
import runs.manager.Main._

case class PathMove(former: Path, current: Path)
case class ConfigTuple(name: String,
                       configScript: Option[String],
                       config: Option[String])
case class DockerPair(containerId: String, volume: String)

case class Existing(name: String, container: String, volume: String)

trait NewCommand {
  def getCommit(implicit blocker: Blocker): IO[String] = {
    val proc: ProcessImpl[IO] =
      Process[IO]("git", List("rev-parse", "HEAD"))
    (proc ># captureOutput).run(blocker) >>= (c => IO.pure(c.output))
  }

  def runThenInsert(newRows: List[String] => List[RunRow],
                    launchDocker: IO[List[DockerPair]],
                    existing: List[DockerPair],
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[Unit] = {
    killProc(existing.map(_.containerId)).run(blocker) >>
      rmVolumeProc(existing.map(_.volume)).run(blocker) >>
      launchDocker.bracketCase { pairs =>
        runInsert(newRows(pairs.map(_.containerId)))
      } {
        case (newRuns, Completed) =>
          putStrLn("Runs successfully inserted into database.") >>
            putStrLn("To follow the current runs execute:") >>
            newRuns
              .traverse(
                (p: DockerPair) =>
                  putStrLn(
                    Console.GREEN + "docker logs -f " + p.containerId + Console.RESET
                )
              )
              .void
        case (newRuns, _) =>
          putStrLn("Inserting runs failed")
          killProc(newRuns.map(_.containerId)).run(blocker) >>
            rmVolumeProc(newRuns.map(_.volume)).run(blocker).void
      }

  }

  def findExisting(names: NonEmptyList[String])(
    implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean
  ): IO[List[Existing]] = {
//    val drop = sql"DROP TABLE IF EXISTS runs".update.run
    val create = RunRow.createTable.update.run
    val fragment =
      fr"SELECT name, containerId, volume  FROM runs WHERE" ++ in(
        fr"name",
        names
      )
    val checkExisting =
      fragment
        .query[Existing]
        .to[List]
    (create, checkExisting).mapN((_, e) => e).transact(xa)
  }

  def checkOverwrite(
    existing: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit] =
    existing match {
      case Nil => IO.unit
      case existing =>
        putStrLn(
          if (yes) "Overwriting the following rows:"
          else "Overwrite the following rows?"
        ) >> existing.traverse(putStrLn) >> pause
    }

  def getCommitMessage(implicit blocker: Blocker): IO[String] = {
    val proc: ProcessImpl[IO] =
      Process[IO]("git", List("log", "-1", "--pretty=%B"))
    (proc ># captureOutput).run(blocker) >>= (m => IO.pure(m.output))
  }

  def getDescription(
    description: Option[String]
  )(implicit blocker: Blocker): IO[String] = {
    description match {
      case Some(d) => IO.pure(d)
      case None    => getCommitMessage(blocker)
    }
  }

  def buildImage(image: String, imageBuildPath: Path, dockerfilePath: Path)(
    implicit blocker: Blocker
  ): IO[String] = {
    val buildProc = Process[IO](
      "docker",
      List(
        "build",
        "-f",
        dockerfilePath.toString,
        "-t",
        image,
        imageBuildPath.toString
      )
    )
    val inspectProc =
      Process[IO]("docker", List("inspect", "--format='{{ .Id }}'", image)) ># captureOutput
    buildProc.run(blocker) *> inspectProc
      .run(blocker)
      .map(_.output)
      .map("'sha256:(.*)'".r.replaceFirstIn(_, "$1"))
  }

  def runProc(dockerRun: List[String]): ProcessImplO[IO, String] = {
    Process[IO](dockerRun.head, dockerRun.tail) ># captureOutput
  }

  def runDocker(
    dockerRunBase: List[String],
    hostVolume: String,
    containerVolume: String,
    image: String,
    config: Option[String]
  )(implicit blocker: Blocker): IO[DockerPair] = {
    val dockerRun = dockerRunBase ++ List(
      "-v",
      s"$hostVolume:$containerVolume",
      image
    ) ++ config.fold(List[String]())(List(_))
    putStrLn("Executing docker command:") >>
      putStrLn(dockerRun.mkString(" ")) >>
      runProc(dockerRun)
        .run(blocker)
        .map(_.output.stripLineEnd)
        .map(DockerPair(_, hostVolume))
  }

  def readPath(path: Path)(implicit blocker: Blocker): IO[String] = {
    readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .compile
      .foldMonoid
  }

  def sampleConfig(configScript: String,
                   interpreter: String,
                   interpreterArgs: List[String],
  )(implicit blocker: Blocker): IO[String] = {
    val runScript: ProcessImplO[IO, String] = Process[IO](
      interpreter,
      interpreterArgs ++ List(configScript)
    ) ># captureOutput
    runScript
      .run(blocker)
      .map(_.output)
  }

  def newCommand(name: String,
                 description: Option[String],
                 image: String,
                 imageBuildPath: Path,
                 dockerfilePath: Path,
                 dockerRunBase: List[String],
                 containerVolume: String,
                 newMethod: NewMethod)(implicit blocker: Blocker,
                                       xa: H2Transactor[IO],
                                       yes: Boolean): IO[ExitCode] = {

    implicit val dockerRun: List[String] = dockerRunBase;
    val configTuplesOp: IO[List[ConfigTuple]] = newMethod match {
      case Single(config: Option[String]) =>
        IO.pure(List(ConfigTuple(name, None, config)))
      case Multi(
          scriptPath: Path,
          interpreter: String,
          args: List[String],
          numRuns: Int
          ) =>
        for {
          script <- readPath(scriptPath)
          configs <- Monad[IO]
            .replicateA(numRuns, sampleConfig(script, interpreter, args))
        } yield
          configs.zipWithIndex
            .map {
              case (config: String, i) =>
                ConfigTuple(
                  name = s"$name${i.toString}",
                  configScript = Some(script),
                  config = Some(config),
                )
            }
    }
    for {
      tuples <- configTuplesOp
      names <- tuples map (_.name) match {
        case h :: t => IO.pure(new NonEmptyList[String](h, t))
        case Nil =>
          IO.raiseError(new RuntimeException("empty ConfigTuples"))
      }
      existing <- findExisting(names)
      _ <- checkOverwrite(existing map (_.name))
      imageId <- buildImage(
        image = image,
        imageBuildPath = imageBuildPath,
        dockerfilePath = dockerfilePath,
      )
      commit <- getCommit
      description <- getDescription(description)
      _ <- runThenInsert(
        newRows = _.zip(tuples)
          .map {
            case (containerId, t) =>
              RunRow(
                commitHash = commit,
                config = t.config,
                configScript = t.configScript,
                containerId = containerId,
                imageId = imageId,
                description = description,
                volume = t.name,
                name = t.name,
              )
          },
        launchDocker = tuples
          .traverse(
            t =>
              runDocker(
                dockerRunBase = dockerRunBase,
                hostVolume = t.name,
                containerVolume = containerVolume,
                image = image,
                config = t.config
            )
          ),
        existing =
          existing.map((e: Existing) => DockerPair(e.container, e.volume)),
      )
    } yield ExitCode.Success
  }

}
