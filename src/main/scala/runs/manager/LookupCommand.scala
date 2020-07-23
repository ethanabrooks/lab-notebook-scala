package runs.manager

import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import runs.manager.Main._

import scala.language.postfixOps

case class LookupPair(name: String, result: String)

trait LookupCommand {
  def lookupCommand(pattern: Option[String], active: Boolean, field: String)(
    implicit blocker: Blocker,
    xa: H2Transactor[IO]
  ): IO[ExitCode] =
    for {
      conditions <- selectConditions(pattern, active)
      results <- (fr"SELECT name," ++ Fragment.const(field) ++ fr"FROM runs" ++ conditions)
        .query[LookupPair]
        .to[List]
        .transact(xa)
      _ <- results.traverse {
        case LookupPair(name, result) =>
          putStrLn(s"${Console.BOLD}$name:${Console.RESET}\t$result")
      }
    } yield ExitCode.Success
}
