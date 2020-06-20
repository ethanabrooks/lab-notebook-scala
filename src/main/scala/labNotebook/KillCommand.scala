package labNotebook

import cats.effect
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

  def killCommand(pattern: String,
                  active: Boolean)(implicit uri: String): IO[ExitCode] = {

    Blocker[IO]
      .use(b => {
        implicit val blocker: Blocker = b
        val ps = Process[IO]("docker", List("ps", "-q")) ># captureOutput
        val nameLikePattern = fr"name LIKE" ++ Fragment.const(s"'$pattern'")
        for {
          conditions <- (if (active)
                           ps.run(b).map { activeIds =>
                             activeIds.output
                               .split("\n")
                               .map(_.stripLineEnd)
                               .map(
                                 id =>
                                   nameLikePattern ++
                                     fr"AND containerId LIKE" ++ Fragment
                                     .const(s"'$id%'")
                               )
                           } else
                           IO.pure {
                             Array(nameLikePattern)
                           })
          pairs <- transactor.use { xa =>
            (fr"SELECT name, containerId FROM runs WHERE" ++
              Fragments.or(conditions.toIndexedSeq: _*))
              .query[(String, String)]
              .to[List]
              .transact(xa)
          }
          containerIds <- pairs.unzip match {
            case (names, containerIds) =>
              putStrLn("Kill the following runs?") >> names
                .traverse(putStrLn) >> readLn >>
                IO.pure(containerIds)
          }
          _ <- killProc(containerIds).run(blocker)
        } yield ExitCode.Success
      })
  };
}
