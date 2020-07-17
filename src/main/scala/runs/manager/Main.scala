package runs.manager

import cats.effect._
import io.github.vigoo.prox.{Process, _}

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    implicit val runner: ProcessRunner[IO] = new JVMProcessRunner

    val cmd =
      "docker run -d --rm --gpus all -it -v debug-docker0:/volume jax"
        .split(" ")
        .toList
    Blocker[IO]
      .use { blocker =>
        Process[IO](cmd.head, cmd.tail).run(blocker)
      }
      .map(_ => ExitCode.Success)
  }
}
