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
import fs2.io.file._
import fs2.{Pipe, text}
import io.github.vigoo.prox.Process.{ProcessImpl, ProcessImplO}
import io.github.vigoo.prox.{Process, ProcessRunner}
import labNotebook.Main.runInsert

case class PathMove(current: Path, former: Path)
case class ConfigTuple(name: String,
                       configScript: Option[String],
                       config: String,
                       logDir: Path)

case class Existing(name: String, container: String, directory: Path)

trait NewCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val cs: ContextShift[IO]
  implicit val runner: ProcessRunner[IO]
  def killProc(ids: List[String]): Process[IO, _, _]
  def recursiveRemove(path: Path)(implicit blocker: Blocker): IO[List[Unit]]
  def pause(implicit yes: Boolean): IO[Unit]
  def killContainers(containers: List[String])(
    implicit blocker: Blocker
  ): IO[Unit]

  def lookupExisting(names: NonEmptyList[String])(
    implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean
  ): IO[List[Existing]] = {
    val drop = sql"DROP TABLE IF EXISTS runs".update.run
    val create = RunRow.createTable.update.run
    val fragment =
      fr"SELECT name, containerId, logDir  FROM runs WHERE" ++ in(
        fr"name",
        names
      )
    val checkExisting =
      fragment
        .query[(String, String, String)]
        .to[List]
    for {
      res <- putStrLn(fragment.toString) >>
        //        drop.transact(xa) >>
        (create, checkExisting).mapN((_, e) => e).transact(xa)
    } yield
      res.map {
        case (name, id, logDir) => Existing(name, id, Paths.get(logDir))
      }
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

  def getCommit(implicit blocker: Blocker): IO[String] = {
    val proc: ProcessImpl[IO] =
      Process[IO]("git", List("rev-parse", "HEAD"))
    (proc ># captureOutput).run(blocker) >>= (c => IO.pure(c.output))
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
    buildProc.run(blocker) *> inspectProc.run(blocker).map(_.output)
  }

  def launchProc(image: String, config: String): ProcessImplO[IO, String] =
    Process[IO](
      "docker",
      List("run", "-d", "--rm", "-it", image) ++ List(config)
    ) ># captureOutput

  def launchRun(config: String,
                image: String)(implicit blocker: Blocker): IO[String] =
    launchProc(image, config).run(blocker) map {
      _.output.stripLineEnd
    }

  def createOps(image: String, config: String, existingDir: Path)(
    implicit blocker: Blocker
  ): Ops

  def tempDirectory(path: Path): Path = {
    Paths.get("/tmp", path.getFileName.toString)
  }

  def stashPath(path: Path)(implicit blocker: Blocker): IO[PathMove] =
    putStrLn(s"Moving $path to ${tempDirectory(path)}...") >>
      move[IO](blocker, path, tempDirectory(path)) >> IO
      .pure(PathMove(path, tempDirectory(path)))

  def readPath(path: Path)(implicit blocker: Blocker): IO[String] = {
    readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .compile
      .foldMonoid
  }

  def manageTempDirectories(
    directoryMoves: IO[List[PathMove]],
    op: List[PathMove] => IO[Unit]
  )(implicit blocker: Blocker): IO[Unit] = {
    directoryMoves.bracketCase {
      op
    } {
      case (directoryMoves, Completed) =>
        putStrLn("Insertion complete. Cleaning up...") >>
          directoryMoves
            .map(_.former)
            .traverse(p => putStrLn(s"Removing $p...") >> recursiveRemove(p))
            .void
      case (directoryMoves, _) =>
        putStrLn("Abort. Restoring old directories...") >>
          directoryMoves.traverse {
            case PathMove(current: Path, former: Path) =>
              putStrLn(s"Moving $former to $current...") >> move[IO](
                blocker,
                former,
                current
              )
          }.void
    }
  }

  def removeDirectoriesOnFail(
    newDirectories: IO[List[Path]],
    op: List[Path] => IO[Unit]
  )(implicit blocker: Blocker): IO[Unit] = {
    newDirectories
      .bracketCase {
        op
      } {
        case (_, Completed) => IO.unit
        case (newDirectories: List[Path], _) =>
          putStrLn("Removing created directories...") >>
            newDirectories
              .traverse(d => putStrLn(d.toString) >> recursiveRemove(d))
              .void
      }
  }

  def killRunsOnFail(
    containerIds: IO[List[String]],
    op: List[String] => IO[Unit]
  )(implicit blocker: Blocker): IO[Unit] = {
    containerIds
      .bracketCase {
        op
      } {
        case (_, Completed) =>
          putStrLn("Runs successfully launched.")
        case (containerIds: List[String], _) =>
          putStrLn("Abort. Killing containers...") >>
            containerIds.traverse(putStrLn) >>
            killProc(containerIds).run(blocker).void
      }
  }

  def killReplacedContainersOnSuccess(
    replacedContainers: List[String],
    op: IO[Unit]
  )(implicit blocker: Blocker): IO[Unit] =
    IO.unit.bracketCase { _ =>
      op
    } {
      case (_, Completed) =>
        putStrLn("Killing replaced containers...") >>
          killContainers(replacedContainers)
      case (_, _) => IO.unit
    }

  def createRows(
    commit: String,
    imageId: String,
    description: String,
    configTuples: List[ConfigTuple]
  )(containerIds: List[String]): List[RunRow] = {
    for {
      (t, containerId) <- configTuples zip containerIds
    } yield
      RunRow(
        commitHash = commit,
        config = t.config,
        configScript = t.configScript,
        containerId = containerId,
        imageId = imageId,
        description = description,
        logDir = t.logDir.toString,
        name = t.name,
      )
  }

  def createBrackets(
    newRows: List[String] => List[RunRow],
    directoryMoves: IO[List[PathMove]],
    newDirectories: IO[List[Path]],
    containerIds: IO[List[String]],
    existingContainers: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[Unit] = {
    val newRunsOp = manageTempDirectories(
      directoryMoves,
      _ =>
        removeDirectoriesOnFail(
          newDirectories,
          _ =>
            killRunsOnFail(
              containerIds,
              (containerIds: List[String]) => runInsert(newRows(containerIds))
          )
      )
    )
    killReplacedContainersOnSuccess(existingContainers, newRunsOp)
  }

  def newCommand(name: String,
                 description: Option[String],
                 logDir: Path,
                 image: String,
                 imageBuildPath: Path,
                 dockerfilePath: Path,
                 newMethod: NewMethod)(implicit blocker: Blocker,
                                       xa: H2Transactor[IO],
                                       yes: Boolean): IO[ExitCode] = {

    val configTuplesOp: IO[List[ConfigTuple]] = newMethod match {
      case FromConfig(config) =>
        IO.pure(
          List(
            ConfigTuple(
              name,
              None,
              config.toList.mkString(" "),
              Paths.get(logDir.toString, name)
            )
          )
        )
      case FromConfigScript(script, interpreter, args, numRuns) =>
        val ids = 0 to numRuns
        val runScript
          : ProcessImplO[IO, String] = Process[IO](interpreter, args) ># captureOutput
        for {
          configScript <- readPath(script)
          configs <- Monad[IO].replicateA(numRuns, runScript.run(blocker))
        } yield {
          val value: List[ConfigTuple] = (ids zip configs).toList
            .map {
              case (suffixInt, runScript) =>
                val suffix = suffixInt.toString
                ConfigTuple(
                  name = name + suffix,
                  configScript = Some(configScript),
                  config = runScript.output,
                  logDir = Paths.get(logDir.toString, suffix)
                )
            }
          value
        }
    }
    for {
      configTuples <- configTuplesOp
      names <- configTuples map (_.name) match {
        case h :: t => IO.pure(new NonEmptyList[String](h, t))
        case Nil =>
          IO.raiseError(new RuntimeException("empty ConfigTuples"))
      }
      existing <- lookupExisting(names)
      _ <- checkOverwrite(existing map (_.name))
      ops: List[Ops] = (configTuples zip existing) map {
        case (t, e) => createOps(image, t.config, existingDir = e.directory)
      }
      imageId <- buildImage(image, imageBuildPath, dockerfilePath)
      commit <- getCommit
      description <- getDescription(description)
      _ <- createBrackets(
        newRows = createRows(commit, imageId, description, configTuples),
        directoryMoves = ops traverse (_.moveDir),
        newDirectories = ops traverse (_.createDir),
        containerIds = ops traverse (_.launchRuns),
        existingContainers = existing map (_.container),
      )
    } yield ExitCode.Success
  }
}
