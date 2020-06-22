package labNotebook

import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import doobie.util.fragment.Fragment
import labNotebook.Main._

import scala.language.postfixOps

trait LookupCommand {
  def lookupCommand(pattern: Option[String], active: Boolean, field: String)(
    implicit blocker: Blocker,
    xa: H2Transactor[IO]
  ): IO[ExitCode] = {
    for {
      conditions <- selectConditions(pattern, active)
      results <- (fr"SELECT" ++ Fragment.const(field) ++ fr"FROM runs" ++ conditions)
        .query[String]
        .to[List]
        .transact(xa)
      _ <- results.traverse(putStrLn)
    } yield ExitCode.Success
  }
}
