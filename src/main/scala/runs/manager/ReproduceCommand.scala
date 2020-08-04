package runs.manager

import java.sql.Timestamp
import java.time.Instant

import cats.data.NonEmptyList
import cats.effect.Console.io.putStrLn
import cats.effect.{Blocker, Clock, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import runs.manager.Main._

import scala.concurrent.duration.SECONDS

trait ReproduceCommand {

  def reproduceCommand(newName: Option[String],
                       pattern: String,
                       active: Boolean,
                       description: Option[String],
                       dockerRunBase: List[String],
                       containerVolume: String,
                       resample: Boolean,
                       interpreter: String,
                       interpreterArgs: List[String],
                       follow: Boolean,
  )(implicit blocker: Blocker,
    xa: H2Transactor[IO],
    yes: Boolean): IO[ExitCode] = {
    IO(ExitCode.Success)
  }
}
