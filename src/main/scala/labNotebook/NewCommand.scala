package labNotebook

import java.nio.file.{Path, Paths}
import cats.Monad
import cats.effect.Console.io.putStrLn
import cats.effect.Console.io.readLn
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

case class PathMove(current: Path, former: Path)

trait NewCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val cs: ContextShift[IO]
  implicit val runner: ProcessRunner[IO]
  def killProc(ids: List[String]): Process[IO, _, _]
  def pause(implicit yes: Boolean): IO[Unit]

  implicit class ConfigMap(map: Map[String, String]) {
    def print(): IO[List[Unit]] = {
      map.toList.traverse {
        case (name, config) =>
          putStrLn(name + ":") >>
            putStrLn(config)
      }
    }
  }

  object ConfigMap {

    def fromConfig(name: String,
                   config: NonEmptyList[String]): Map[String, String] =
      Map(name -> config.toList.mkString(" "))

    def fromConfigScript(
      configScript: String,
      interpreter: String,
      interpreterArgs: List[String],
      numRuns: Int,
      name: String
    )(implicit blocker: Blocker): IO[Map[String, String]] = {
      val args = interpreterArgs ++ List(configScript)
      val process = Process[IO](interpreter, args) ># captureOutput
      for {
        results <- Monad[IO]
          .replicateA(numRuns, process.run(blocker))
      } yield {
        results.zipWithIndex.map {
          case (result, i) => (s"$name$i", result.output)
        }.toMap
      }
    }

    def build(name: String, configSource: NewMethod)(
      implicit blocker: Blocker
    ): IO[Map[String, String]] = {
      configSource match {
        case FromConfig(config) =>
          IO.pure(ConfigMap.fromConfig(name, config))
        case FromConfigScript(configScript, interpreter, args, numRuns) =>
          readPath(configScript) >>= { cs =>
            ConfigMap.fromConfigScript(
              interpreter = interpreter,
              interpreterArgs = args,
              configScript = cs,
              numRuns = numRuns,
              name = name
            )
          }
      }
    }
  }

  private def getNames(
    configMap: Map[String, String]
  ): IO[NonEmptyList[String]] = {
    configMap.keys.toList.toNel match {
      case None        => IO.raiseError(new RuntimeException("Empty configMap"))
      case Some(names) => IO.pure(names)
    }
  }

  def findExisting(names: NonEmptyList[String])(
    implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean
  ): IO[(List[String], List[String])] = {
    val drop = sql"DROP TABLE IF EXISTS runs".update.run
    val create = RunRow.createTable.update.run
    val fragment =
      fr"SELECT name, logDir FROM runs WHERE" ++ in(fr"name", names)
    val checkExisting =
      fragment
        .query[(String, String)]
        .to[List]
    for {
      res <- putStrLn(fragment.toString) >>
//        drop.transact(xa) >>
        (create, checkExisting).mapN((_, e) => e).transact(xa)
    } yield res.unzip
  }

  def checkOverwrite(
    existing: List[String],
    dirs: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit] =
    if (existing.isEmpty) IO.unit
    else
      putStrLn(
        if (yes) "Overwriting the following rows:"
        else "Overwrite the following rows?"
      ) >> existing.traverse(putStrLn) >> dirs
        .traverse(putStrLn) >> pause

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

  def readConfigScript(
    newMethod: NewMethod
  )(implicit blocker: Blocker): IO[Option[String]] = {
    newMethod match {
      case FromConfig(_) => IO.pure(None)
      case FromConfigScript(configScript, _, _, _) =>
        readPath(configScript).map(Some(_))
    }
  }

  def buildImage(
    configMap: Map[String, String],
    image: String,
    imageBuildPath: Path,
    dockerfilePath: Path
  )(implicit blocker: Blocker): IO[String] = {
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

  def launchRuns(
    configMap: Map[String, String],
    image: String,
    imageBuildPath: Path,
    dockerfilePath: Path
  )(implicit blocker: Blocker): IO[List[String]] = {
    val dockerBuild =
      Process[IO](
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
    for {
      results <- dockerBuild.run(blocker) >> configMap.values.toList
        .traverse { config =>
          launchProc(image, config).run(blocker)
        }
    } yield results.map(_.output.stripLineEnd)
  }

  def tempDirectory(path: Path): Path = {
    Paths.get("/tmp", path.getFileName.toString)
  }

  def stashPaths(
    paths: List[Path]
  )(implicit blocker: Blocker): IO[List[PathMove]] =
    paths.traverse(p => {
      putStrLn(s"Moving $p to ${tempDirectory(p)}...")
      move[IO](blocker, p, tempDirectory(p)) >> IO
        .pure(PathMove(p, tempDirectory(p)))
    })

  def readPath(path: Path)(implicit blocker: Blocker): IO[String] = {
    readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .compile
      .foldMonoid
  }

  def createNewDirectories(logDir: Path, configMap: Map[String, String])(
    implicit blocker: Blocker
  ): IO[List[Path]] =
    createDirectories[IO](blocker, logDir) *> directoryStream[IO](
      blocker,
      logDir
    ).compile.toList.map(_.length) >>= { (start: Int) =>
      configMap.toList.zipWithIndex
        .traverse {
          case (_, i) =>
            val path = Paths.get(logDir.toString, (start + i).toString)
            putStrLn(s"Creating Directory $path...") >>
              createDirectories[IO](blocker, path)

        }
    }

  def recursiveRemove(path: Path)(implicit blocker: Blocker): IO[List[Unit]] =
    walk[IO](blocker, path).compile.toList >>= {
      _.traverse(delete[IO](blocker, _))
    }

  def insertNewRuns(commit: String,
                    description: String,
                    configScript: Option[String],
                    configMap: Map[String, String],
                    imageId: String,
  )(newDirectories: List[Path])(
    containerIds: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit] = {
    val newRows = for {
      (id, (logDir, (name, config))) <- containerIds zip (newDirectories zip configMap)
    } yield
      RunRow(
        commitHash = commit,
        config = config,
        configScript = configScript,
        containerId = id,
        imageId = id,
        description = description,
        logDir = logDir.toString,
        name = name,
      )
    val insert: doobie.ConnectionIO[Int] =
      RunRow.mergeCommand.updateMany(newRows)
    val ls =
      sql"SELECT name FROM runs"
        .query[String]
        .to[List]
    insert.transact(xa).void >> (
      ls.transact(xa) >>= (
        _ map ("new run:" + _) traverse putStrLn
      )
    ).void // TODO
  }

  def safeMoveOldDirectories(
    directoryMoves: IO[List[PathMove]],
    insertNewRuns: List[PathMove] => IO[Unit]
  )(implicit blocker: Blocker): IO[Unit] = {
    directoryMoves.bracketCase {
      insertNewRuns
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

  def safeCreateDirectories(
    newDirectories: IO[List[Path]],
    insertNewRuns: List[Path] => IO[Unit]
  )(implicit blocker: Blocker): IO[Unit] = {
    newDirectories
      .bracketCase {
        insertNewRuns
      } {
        case (newDirectories, Completed) =>
          putStrLn("Created directories:") >> newDirectories
            .map(_.toAbsolutePath.toString)
            .traverse(putStrLn)
            .void
        case (newDirectories: List[Path], _) =>
          putStrLn("Removing created directories...") >>
            newDirectories
              .traverse(d => putStrLn(d.toString) >> recursiveRemove(d))
              .void
      }
  }

  def safeLaunchRuns(
    containerIds: IO[List[String]],
    insertNewRuns: List[Path] => List[String] => IO[Unit]
  )(newDirectories: List[Path])(implicit blocker: Blocker): IO[Unit] = {
    containerIds
      .bracketCase {
        insertNewRuns(newDirectories)
      } {
        case (_, Completed) =>
          putStrLn("Runs successfully launched.")
        case (containerIds: List[String], _) =>
          putStrLn("Abort. Killing containers") >>
            containerIds.traverse(putStrLn) >>
            killProc(containerIds).run(blocker).void
      }
  }

  def newCommand(name: String,
                 description: Option[String],
                 logDir: Path,
                 image: String,
                 imageBuildPath: Path,
                 dockerfilePath: Path,
                 newMethod: NewMethod)(implicit blocker: Blocker,
                                       xa: H2Transactor[IO],
                                       yes: Boolean): IO[ExitCode] =
    for {
      configMap <- ConfigMap.build(name, newMethod)
      names <- getNames(configMap)
      pairs <- findExisting(names)
      (existingNames, existingDirectories) = pairs
      _ <- checkOverwrite(existingNames, existingDirectories)
      commit <- getCommit
      description <- getDescription(description)
      configScript <- readConfigScript(newMethod)
      imageId <- buildImage(
        configMap = configMap,
        image = image,
        imageBuildPath = imageBuildPath,
        dockerfilePath = dockerfilePath
      )
      directoryMoves: IO[List[PathMove]] = stashPaths(
        existingDirectories.map(Paths.get(_))
      )
      newDirectories: IO[List[Path]] = createNewDirectories(logDir, configMap)
      containerIds: IO[List[String]] = launchRuns(
        configMap = configMap,
        image = image,
        imageBuildPath = imageBuildPath,
        dockerfilePath = dockerfilePath
      )
      insertionOp: (List[Path] => List[String] => IO[Unit]) = insertNewRuns(
        commit = commit,
        description = description,
        configScript = configScript,
        configMap = configMap,
        imageId = imageId
      )
      _ <- safeMoveOldDirectories(directoryMoves, _ => {
        safeCreateDirectories(
          newDirectories,
          safeLaunchRuns(containerIds, insertionOp)
        )
      })
    } yield ExitCode.Success
}
