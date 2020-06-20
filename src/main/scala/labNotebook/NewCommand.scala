package labNotebook

import java.io.File
import java.nio.file.{Path, Paths}

import cats.{Monad, effect}
import cats.effect.Console.io.putStrLn
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, ContextShift, ExitCode, IO}
import cats.implicits._
import doobie._
import Fragments.in
import doobie.h2.H2Transactor
import doobie.implicits._
import fs2.io.file.{createDirectory, directoryStream, readAll, walk, delete}
import fs2.{Pipe, text}
import io.github.vigoo.prox.Process.{ProcessImpl, ProcessImplO}
import io.github.vigoo.prox.{Process, ProcessRunner}

import scala.language.postfixOps

trait NewCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val cs: ContextShift[IO]
  implicit val runner: ProcessRunner[IO]
  def wait(implicit yes: Boolean): IO[Unit]

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
    def build(
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

  def killProc(ids: List[String]): Process[IO, _, _]

  def launchProc(image: String, config: String): ProcessImplO[IO, String] =
    Process[IO](
      "docker",
      List("run", "-d", "--rm", "-it", image) ++ List(config)
    ) ># captureOutput

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
      results <- dockerBuild.run(blocker) >> configMap.values.toList.traverse {
        config =>
          launchProc(image, config).run(blocker)
      }
    } yield results.map(_.output.stripLineEnd)
  }

  def readPath(path: Path)(implicit blocker: Blocker): IO[String] = {
    readAll[IO](path, blocker, 4096)
      .through(text.utf8Decode)
      .compile
      .foldMonoid
  }

  def createNewDirectories(logDir: Path, configMap: Map[String, String])(
    implicit blocker: Blocker
  ): IO[List[Path]] =
    directoryStream[IO](blocker, logDir).compile.toList.map(_.length) >>= {
      (start: Int) =>
        configMap.toList.zipWithIndex
          .traverse {
            case (_, i) =>
              createDirectory[IO](
                blocker,
                Paths.get(logDir.toString, (start + i).toString)
              )

          }
    }

  def recursiveRemove(path: Path)(implicit blocker: Blocker): IO[List[Unit]] =
    walk[IO](blocker, path).compile.toList >>= {
      _.traverse(delete[IO](blocker, _))
    }

  def removeDirectories(
    directories: List[Path]
  )(implicit blocker: Blocker): IO[_] = {
    directories.traverse(recursiveRemove(_).void)
  }

  def insertNewRuns(commit: String,
                    description: String,
                    configScript: Option[String],
                    configMap: Map[String, String],
                    imageId: String,
  )(
    containerIds: List[String]
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit] = {
    val newRows = for {
      (id, (name, config)) <- containerIds zip configMap
    } yield
      RunRow(
        checkpoint = None,
        commitHash = commit,
        config = config,
        configScript = configScript,
        containerId = id,
        imageId = id,
        description = description,
        events = None,
        name = name,
      )
    configMap.keys.toList.toNel match {
      case None => IO.raiseError(new RuntimeException("Empty configMap"))
      case Some(names) =>
        val drop = sql"DROP TABLE IF EXISTS runs".update.run
        val create: doobie.ConnectionIO[Int] = RunRow.createTable.update.run
        val checkExisting =
          (fr"SELECT name FROM runs WHERE" ++ in(fr"name", names))
            .query[String]
            .to[List]
        val insert: doobie.ConnectionIO[Int] =
          RunRow.mergeCommand.updateMany(newRows)
        val ls =
          sql"SELECT name FROM runs"
            .query[String]
            .to[List]
        for {
          _ <- drop.transact(xa) //TODO
          existing <- (create, checkExisting)
            .mapN((_, e) => e)
            .transact(xa)
          _ <- {
            if (existing.isEmpty) { IO.unit } else {
              putStrLn(
                if (yes) "Overwriting the following rows:"
                else "Overwrite the following rows?"
              ) >> existing.traverse(putStrLn) >> wait
            }
          } >> insert.transact(xa)
          _ <- ls.transact(xa) >>= (_ traverse (x => putStrLn("new run: " + x))) //TODO
        } yield ()
    }
  }

  def newRuns(configMap: Map[String, String],
              containerIds: IO[List[String]],
              insertNewRuns: List[String] => IO[Unit])(implicit
                                                       blocker: Blocker,
  ): IO[Unit] = {
    containerIds
      .bracketCase {
        insertNewRuns
      } {
        case (_, Completed) =>
          putStrLn("IO operations complete.")
        case (containerIds: List[String], _) =>
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
      pair <- newMethod match {
        case FromConfig(config) =>
          val configMap = Map(name -> config.toList.mkString(" "))
          IO.pure((none, configMap))
        case FromConfigScript(configScript, interpreter, args, numRuns) =>
          for {
            configScript <- readPath(configScript.toAbsolutePath)
            configMap <- ConfigMap.build(
              interpreter = interpreter,
              interpreterArgs = args,
              configScript = configScript,
              numRuns = numRuns,
              name = name
            )
          } yield (Some(configScript), configMap)
      }
      (configScript, configMap) = pair
      _ <- putStrLn(
        if (yes) "Creating the following runs:"
        else "Create the following runs?"
      ) >>
        configMap.print() >>
        wait
      commit <- getCommit
      description <- getDescription(description)
      imageId <- buildImage(
        configMap = configMap,
        image = image,
        imageBuildPath = imageBuildPath,
        dockerfilePath = dockerfilePath
      )
      containerIds = launchRuns(
        configMap = configMap,
        image = image,
        imageBuildPath = imageBuildPath,
        dockerfilePath = dockerfilePath
      )
      _ <- newRuns(
        configMap = configMap,
        containerIds = containerIds,
        insertNewRuns = insertNewRuns(
          commit = commit,
          description = description,
          configScript = configScript,
          configMap = configMap,
          imageId = imageId
        )
      )
    } yield ExitCode.Success
}
