package runs.manager

import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import runs.manager.Main._

import scala.language.postfixOps

trait LsCommand {
  def lsCommand(
    pattern: Option[String],
    active: Boolean
  )(implicit blocker: Blocker, xa: H2Transactor[IO]): IO[ExitCode] = {
    for {
      conditions <- selectConditions(pattern, active)
      names <- (fr"SELECT name FROM runs" ++ conditions ++ fr"ORDER BY datetime")
        .query[String]
        .to[List]
        .transact(xa)
      _ <- names.traverse(putStrLn)
    } yield ExitCode.Success
  }
}
