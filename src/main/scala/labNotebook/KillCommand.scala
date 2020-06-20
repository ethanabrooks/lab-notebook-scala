package labNotebook

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import doobie._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.{Process, ProcessRunner}

import scala.language.postfixOps

trait KillCommand {
  val captureOutput: Pipe[IO, Byte, String]
  implicit val runner: ProcessRunner[IO]
  implicit val cs: ContextShift[IO]

  def transactor(implicit uri: String,
                 blocker: Blocker): Resource[IO, H2Transactor[IO]]

  def killProc(ids: List[String]): Process[IO, _, _]

  def killCommand(pattern: String)(implicit uri: String): IO[ExitCode] =
    Blocker[IO].use(b => {
      implicit val blocker: Blocker = b
      val ps = Process[IO]("docker", List("ps", "-q")) ># captureOutput
      ps.run(blocker) >>= {
        activeIds =>
          transactor.use {
            xa =>
              val conditions = activeIds.output
                .split("\n")
                .map(_.stripLineEnd)
                .map(
                  id =>
                    fr"containerId LIKE" ++ Fragment
                      .const(s"'$id%'") ++ fr"AND name LIKE" ++ Fragment
                      .const(s"'$pattern'")
                )
              (fr"SELECT name, containerId FROM runs WHERE" ++
                Fragments.or(conditions.toIndexedSeq: _*))
                .query[(String, String)]
                .to[List]
                .transact(xa)
          }
      } >>= { pairs =>
        pairs.unzip match {
          case (names, containerIds) =>
            putStrLn("Kill the following runs?") >> names
              .traverse(putStrLn) >> readLn >>
              IO.pure(containerIds)
        }
      } >>= { killProc(_).run(blocker) }
    } as ExitCode.Success)
}
