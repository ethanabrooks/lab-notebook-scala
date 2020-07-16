package runs.manager

import java.nio.file.Path

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import com.monovore.decline._
import com.monovore.decline.effect._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import doobie.{ConnectionIO, ExecutionContexts, Fragments}
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImplO
import io.github.vigoo.prox.{JVMProcessRunner, Process, ProcessRunner}

import scala.language.postfixOps

case class Ops(moveDir: IO[Option[PathMove]],
               createDir: IO[Path],
               launchRuns: IO[DockerPair])

case class NameContainer(name: String, containerId: String)

object Main
    extends CommandIOApp(
      name = "runs",
      header = "Manages long-running processes (runs).",
    )
    with MainOpts
    with NewCommand
    with LsCommand
    with LookupCommand
    with RmCommand
    with MvCommand
    with KillCommand
    with ReproduceCommand {

  val captureOutput: Pipe[IO, Byte, String] = fs2.text.utf8Decode[IO]
  implicit val cs: ContextShift[IO] =
    IO.contextShift(ExecutionContexts.synchronous)
  implicit val runner: ProcessRunner[IO] = new JVMProcessRunner
  def pause(implicit yes: Boolean): IO[Unit] = if (yes) IO.unit else readLn.void

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

  def nameContainerQuery(
    conditions: Fragment
  ): ConnectionIO[List[NameContainer]] =
    (fr"SELECT name, containerId FROM runs" ++ conditions)
      .query[NameContainer]
      .to[List]

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

  def rmVolumeProc(volumes: List[String]): Process[IO, _, _] =
    Process[IO]("docker", List("volume", "rm") ++ volumes)

  def killProc(ids: List[String]): Process[IO, _, _] =
    Process[IO]("docker", "kill" :: ids)

  def runInsert(newRows: List[RunRow])(implicit blocker: Blocker,
                                       xa: H2Transactor[IO]): IO[Unit] = {
    val insert: doobie.ConnectionIO[Int] =
      RunRow.mergeCommand.updateMany(newRows)
    val ls =
      sql"SELECT name FROM runs"
        .query[String]
        .to[List]
    insert.transact(xa).void >> (
      ls.transact(xa) >>= (
        _ map ("Runs in db:" + _) traverse putStrLn
      )
    ).void // TODO
  }

  override def main: Opts[IO[ExitCode]] = opts.map {
    case AllOpts(dbPath, server, y, sub) =>
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
            case NewOpts(
                name: String,
                description: Option[String],
                image: String,
                imageBuildPath: Path,
                dockerfilePath: Path,
                dockerRunCommand: List[String],
                volume: String,
                newMethod: NewMethod
                ) =>
              newCommand(
                name = name,
                description = description,
                image = image,
                imageBuildPath = imageBuildPath,
                dockerfilePath = dockerfilePath,
                dockerRunBase = dockerRunCommand,
                containerVolume = volume,
                newMethod = newMethod
              )
            case LsOpts(pattern, active) => lsCommand(pattern, active)
            case LookupOpts(pattern, active, field) =>
              lookupCommand(pattern, active, field)
            case RmOpts(pattern, active) => rmCommand(pattern, active)
            case MvOpts(pattern, active, regex, replace) =>
              mvCommand(pattern, active, regex, replace)
            case KillOpts(pattern, active) => killCommand(pattern, active)
            case ReproduceOpts(
                name: Option[String],
                pattern: String,
                active: Boolean,
                description: Option[String],
                resample: Boolean,
                dockerRunCommand: List[String],
                containerVolume: String,
                interpreter: String,
                interpreterArgs: List[String]
                ) =>
              reproduceCommand(
                newName = name,
                pattern = pattern,
                active = active,
                description = description,
                dockerRunBase = dockerRunCommand,
                containerVolume = containerVolume,
                resample = resample,
                interpreter = interpreter,
                interpreterArgs = interpreterArgs,
              )
          }
        }
      }
  }
}
