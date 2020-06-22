package labNotebook

import java.nio.file.{Path, Paths}

import cats.Monad
import cats.effect.Console.io.{putStrLn, readLn}
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

case class PathMove(former: Path, current: Path)
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
  def getCommit(implicit blocker: Blocker): IO[String]
  def newDirectories(logDir: Path, num: Int)(
    implicit blocker: Blocker
  ): IO[List[Path]]
  def createOps(image: String, config: String, path: Path)(
    implicit blocker: Blocker
  ): Ops
  def createBrackets(
    newRows: List[String] => List[RunRow],
    directoryMoves: IO[List[Option[PathMove]]],
    newDirectories: IO[List[Path]],
    containerIds: IO[List[String]],
    existingContainers: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[Unit]

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

  def tempDirectory(path: Path): Path = {
    Paths.get("/tmp", path.getFileName.toString)
  }

  def stashPath(path: Path)(implicit blocker: Blocker): IO[Option[PathMove]] =
    for {
      exists <- exists[IO](blocker, path)
      r <- if (exists)
        putStrLn(s"Moving $path to ${tempDirectory(path)}...") >>
          move[IO](blocker, path, tempDirectory(path))
            .map(p => Some(PathMove(former = p, current = tempDirectory(p))))
      else IO.pure(None)
    } yield r

  def readPath(path: Path)(implicit blocker: Blocker): IO[String] = {
    readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .compile
      .foldMonoid
  }

  def manageTempDirectories(
    directoryMoves: IO[List[Option[PathMove]]],
    op: List[Option[PathMove]] => IO[Unit]
  )(implicit blocker: Blocker): IO[Unit] = {
    directoryMoves.bracketCase {
      op
    } {
      case (directoryMoves, Completed) =>
        putStrLn("Insertion complete. Cleaning up...") >>
          directoryMoves.traverse {
            case None => IO.unit
            case Some(PathMove(current: Path, _: Path)) =>
              putStrLn(s"Removing $current...") >> recursiveRemove(current)

          }.void
      case (directoryMoves, _) =>
        putStrLn("Abort. Restoring old directories...") >>
          directoryMoves.traverse {
            case None => IO.unit
            case Some(PathMove(current: Path, former: Path)) =>
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
        for {
          configScript <- readPath(script)
          runScript: ProcessImplO[IO, String] = Process[IO](
            interpreter,
            args ++ List(configScript)
          ) ># captureOutput
          configs <- Monad[IO].replicateA(numRuns, runScript.run(blocker))
          logDirs <- newDirectories(logDir, numRuns)
        } yield
          (configs.zipWithIndex zip logDirs)
            .map {
              case ((runScript, i), logDir) =>
                ConfigTuple(
                  name = s"$name${i.toString}",
                  configScript = Some(configScript),
                  config = runScript.output,
                  logDir = logDir
                )
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
      newTuples = configTuples.map(
        t =>
          ConfigTuple(
            name = t.name,
            configScript = t.configScript,
            config = t.config,
            logDir = existing
              .find(_.name == t.name)
              .map(_.directory)
              .getOrElse(t.logDir)
        )
      )
      ops: List[Ops] = newTuples.map(t => {
        createOps(image, t.config, path = t.logDir)
      })
      imageId <- buildImage(image, imageBuildPath, dockerfilePath)
      commit <- getCommit
      description <- getDescription(description)
      _ <- createBrackets(
        newRows = _.zip(newTuples)
          .map {
            case (containerId, t) =>
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
          },
        directoryMoves = ops.traverse(_.moveDir),
        newDirectories = ops.traverse(_.createDir),
        containerIds = ops.traverse(_.launchRuns),
        existingContainers = existing.map(_.container),
      )
    } yield ExitCode.Success
  }
}
