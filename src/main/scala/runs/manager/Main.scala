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
import io.github.vigoo.prox.{
  JVMProcessRunner,
  Process,
  ProcessResult,
  ProcessRunner
}

import scala.language.postfixOps
import scala.util.matching.Regex

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

  override def main: Opts[IO[ExitCode]] = opts.map {
    case AllOpts(dbPath, server, y, sub) =>
          su match {
            case NewOpts(
                containerVolume: String,
                description: Option[String],
                dockerfilePath: Path,
                dockerRunCommand: List[String],
                follow: Boolean,
                hostVolume: Option[String],
                image: String,
                imageBuildPath: Path,
                killLabel: Option[String],
                name: String,
                newMethod: NewMethod
                ) =>
              newCommand(
                name = name,
                description = description,
                image = image,
                imageBuildPath = imageBuildPath,
                dockerfilePath = dockerfilePath,
                dockerRunBase = dockerRunCommand,
                hostVolume = hostVolume,
                containerVolume = containerVolume,
                follow = follow,
                killLabel = killLabel,
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
                resample: Boolea,
                dockerRunCommand: List[String],
                containerVolume: String,
                interpreter: String,
                interpreterArgs: List[String],
                follow: Boolean
                ) =>
              IO.raiseError(new RuntimeException("not implemented"))
        }
      }
}
