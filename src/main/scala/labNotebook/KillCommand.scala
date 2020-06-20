package labNotebook

import java.nio.file.Path

import cats.effect
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import fs2.Pipe
import io.github.vigoo.prox.{Process, ProcessRunner}

import cats.Monad
import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.ExitCase.Completed
import cats.effect.{Blocker, ContextShift, ExitCode, IO, Resource}
import cats.implicits._
import doobie._
import Fragments.in
import doobie.h2.H2Transactor
import doobie.implicits._
import fs2.Pipe
import io.github.vigoo.prox.Process.ProcessImpl
import io.github.vigoo.prox.{Process, ProcessRunner}

import scala.io.BufferedSource
import scala.language.postfixOps

import scala.language.postfixOps

trait KillCommand {
  implicit val runner: ProcessRunner[IO]
  implicit val cs: ContextShift[IO]

  def transactor(implicit uri: String,
                 blocker: Blocker): Resource[IO, H2Transactor[IO]]

  def killProc(ids: List[String]): Process[IO, _, _]

  def killCommand(pattern: String)(implicit uri: String): IO[ExitCode] =
    Blocker[IO].use(b => {
      implicit val blocker: Blocker = b
      Process[IO]("docker", List("ps", "-q")).run(blocker) >>= {
        activeIds =>
          transactor.use {
            xa =>
              val fragment = fr"SELECT name, containerId FROM runs WHERE" ++
//                Fragment .const(s"'$pattern'") ++ fr"AND (" ++
                Fragments.or(
                  fr"containerId LIKE 'e47ab214bdd0%' AND name LIKE" ++ Fragment
                    .const(s"'$pattern'"),
                  fr"containerId LIKE 'e47ab214bdd0%' AND name LIKE" ++ Fragment
                    .const(s"'$pattern'"),
                )
              fragment
                .query[(String, String)]
                .to[List]
                .transact(xa)
          }
      } >>= { pairs =>
        pairs.unzip match {
          case (names, containerIds) =>
            putStrLn("Kill the following runs?") >> names
              .traverse(putStrLn) >> containerIds
              .traverse(putStrLn) >> readLn >>
              IO.pure(containerIds)
        }
      } >>= { killProc(_).run(blocker) }
    } as ExitCode.Success)
}
