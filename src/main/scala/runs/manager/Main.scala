package runs.manager

import cats.effect.{ExitCode, IO}
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp

object Main
    extends CommandIOApp(
      name = "runs",
      header = "Manages long-running processes (runs).",
    ) {

  case class AllOpts(string: String)

  override def main: Opts[IO[ExitCode]] =
    Opts
      .option[String]("string", "a string that may contain flags", short = "s")
      .map(AllOpts)
      .map(_ => IO.pure(ExitCode.Success))
}
