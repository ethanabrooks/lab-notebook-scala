package labNotebook

import java.nio.file.{Path, Paths}

import cats.data.NonEmptyList
import cats.effect.Console.io.readLn
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.{ConnectionIO, ExecutionContexts, Fragments}
import fs2.Pipe
import fs2.io.file.{delete, walk}
import io.github.vigoo.prox.Process.ProcessImplO
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}

import scala.language.postfixOps

object Main
    extends CommandIOApp(
      name = "run-manager",
      header = "Manages long-running processes (runs).",
    )
    with MainOpts
    with NewCommand
    with LsCommand
    with RmCommand
    with KillCommand
    with ReproduceCommand {

  val captureOutput: Pipe[IO, Byte, String] = fs2.text.utf8Decode[IO]
  implicit val cs: ContextShift[IO] =
    IO.contextShift(ExecutionContexts.synchronous)
  implicit val runner: ProcessRunner[IO] = new JVMProcessRunner
  def pause(implicit yes: Boolean): IO[Unit] = if (yes) IO.unit else readLn.void

  def recursiveRemove(path: Path)(implicit blocker: Blocker): IO[List[Unit]] =
    walk[IO](blocker, path).compile.toList >>= {
      _.traverse(delete[IO](blocker, _))
    }

  def selectConditions(pattern: Option[String], active: Boolean)(
    implicit blocker: Blocker
  ): IO[Fragment] = {
    val nameLikePattern: Option[Fragment] =
      pattern.map(p => fr"name LIKE $p")
    if (active) {
      activeContainers.map {
        case Nil => fr"WHERE FALSE"
        case activeIds =>
          val fragments: List[Fragment] = activeIds
            .map(id => {
              val condition = fr"containerId LIKE ${id + "%"}"
              nameLikePattern.fold(condition)(condition ++ fr"AND" ++ _)
            })
          val orClauses = Fragments.or(fragments.toArray.toIndexedSeq: _*)
          fr"WHERE" ++ orClauses
      }
    } else {
      IO.pure(nameLikePattern.fold(fr"")(fr"WHERE" ++ _))
    }
  }

  def killContainers(
    containers: List[String]
  )(implicit blocker: Blocker): IO[Unit] = {
    activeContainers map { activeContainers =>
      containers
        .filter(existing => activeContainers.exists(existing.startsWith))
    } >>= {
      case Nil        => IO.unit
      case containers => killProc(containers).run(blocker).void
    }
  }

  def rmStatement(names: List[String]): ConnectionIO[_] = {
    val conditions = names.map(name => fr"name = $name")
    val statement = fr"DELETE FROM runs where" ++ Fragments
      .or(conditions.toIndexedSeq: _*)
    statement.update.run
  }

  def lookupNameContainerLogDir(
    conditions: Fragment
  ): ConnectionIO[List[(String, String, String)]] = {
    (fr"SELECT name, containerId, logDir FROM runs" ++ conditions)
      .query[(String, String, String)]
      .to[List]
  }

  def dockerPsProc: ProcessImplO[IO, String] =
    Process[IO]("docker", List("ps", "-q")) ># captureOutput

  def activeContainers(implicit blocker: Blocker): IO[List[String]] =
    dockerPsProc
      .run(blocker)
      .map(_.output)
      .map {
        case ""       => List()
        case nonEmpty => nonEmpty.split("\n").map(_.stripLineEnd).toList
      }

  def killProc(ids: List[String]): Process[IO, _, _] =
    Process[IO]("docker", "kill" :: ids)

  def createRuns(
    configMap: Map[String, String],
    description: Option[String],
    configScript: Option[String],
    image: String,
    imageId: String,
    logDir: Path
  )(implicit blocker: Blocker, xa: H2Transactor[IO], yes: Boolean): IO[Unit] = {
    for {
      names <- getNames(configMap)
      existing <- findExisting(names)
      (existingNames, existingContainers, existingDirectories) = existing
      _ <- checkOverwrite(existingNames)
      commit <- getCommit
      description <- getDescription(description)
      directoryMoves: IO[List[PathMove]] = stashPaths(
        existingDirectories.map(Paths.get(_))
      )
      newDirectories: IO[List[Path]] = createNewDirectories(logDir, configMap)
      containerIds: IO[List[String]] = launchRuns(
        configMap = configMap,
        image = image,
      )
      insertionOp = insertNewRuns(
        commit = commit,
        description = description,
        configScript = configScript,
        configMap = configMap,
        imageId = imageId
      ): (List[Path], List[String]) => IO[Unit]
      newRunsOp = manageTempDirectories(
        directoryMoves,
        _ =>
          removeDirectoriesOnFail(
            newDirectories,
            (newDirectories: List[Path]) =>
              killRunsOnFail(
                containerIds,
                (containerIds: List[String]) =>
                  insertionOp(newDirectories, containerIds)
            )
        )
      )
      _ <- killReplacedContainersOnSuccess(existingContainers, newRunsOp)
    } yield IO.unit
  }

  override def main: Opts[IO[ExitCode]] = opts.map {
    case AllOpts(dbPath, server, y, logDir, sub) =>
      implicit val yes: Boolean = y
      val uri: String =
        "jdbc:h2:%s%s;DB_CLOSE_DELAY=-1".format(if (server) {
          s"tcp://localhost/"
        } else {
          ""
        }, dbPath)

      Blocker[IO].use { b =>
        implicit val blocker: Blocker = b
        val transactor: Resource[IO, H2Transactor[IO]] = for {
          ce <- ExecutionContexts.fixedThreadPool[IO](32) // our connect EC
          xa <- H2Transactor.newH2Transactor[IO](
            uri, // connect URL
            "sa", // username
            "", // password
            ce, // await connection here
            blocker // execute JDBC operations here
          )
        } yield xa
        transactor.use { x =>
          implicit val xa: H2Transactor[IO] = x
          sub match {
            case New(
                name,
                description,
                image,
                imageBuildPath,
                dockerfilePath,
                newMethod: NewMethod
                ) =>
              newCommand(
                name = name,
                description = description,
                logDir = logDir,
                image = image,
                imageBuildPath = imageBuildPath,
                dockerfilePath = dockerfilePath,
                newMethod = newMethod
              )
            case LsOpts(pattern, active) => lsCommand(pattern, active)
            case RmOpts(pattern, active) => rmCommand(pattern, active)
            case KillOpts(pattern)       => killCommand(pattern)
            case ReproduceOpts(pattern, active) =>
              reproduceCommand(pattern, active)
          }
        }
      }
  }
}
