package labNotebook
import java.nio.file.{Files, Path}

import cats.data.NonEmptyList
import cats.implicits._
import com.monovore.decline.Opts

abstract class NewMethod
case class FromConfig(config: NonEmptyList[String]) extends NewMethod
case class FromConfigScript(configScript: Path,
                            configScriptInterpreter: String,
                            configScriptInterpreterArgs: List[String],
                            numRuns: Int)
    extends NewMethod

abstract class SubCommand

case class New(name: String,
               description: Option[String],
               image: String,
               imageBuildPath: Path,
               DockerfilePath: Path,
               newMethod: NewMethod)
    extends SubCommand

case class BuildImage(dockerFile: Option[String], path: String)
    extends SubCommand

trait LabNotebookOpts {

  case class AllOpts(dbPath: Path, sub: SubCommand)
  val dbPathOpts: Opts[Path] =
    Opts
      .env[Path](
        "RUN_DB_PATH",
        "Path to database file (driver='com.mysql.jdbc.<this arg>')."
      )
  val nameOpts: Opts[String] = Opts
    .option[String]("name", "Name and primary key of run.", short = "n")
  val descriptionOpts: Opts[Option[String]] = Opts
    .option[String]("description", "Optional description of run.", short = "d")
    .orNone

  val imageBuildPathOpts: Opts[Path] = Opts
    .env[Path]("RUN_IMAGE_BUILD_PATH", "Where to perform docker build.")
  //TODO: validation

  val dockerfilePathOpts: Opts[Path] = Opts
    .env[Path]("RUN_DOCKERFILE_PATH", "Path to Dockerfile.")
  //TODO: validation

  val imageOpts: Opts[String] = Opts
    .env[String]("RUN_IMAGE", "Docker image.")

  val killOpts: Opts[Path] = Opts
    .env[Path](
      "RUN_KILL_SCRIPT",
      "Path to executable script that kills a run: " +
        """
    | ❯ $RUN_KILL_SCRIPT <output of launch script>""".stripMargin
    )
    .validate("Path does not exit.")(Files.exists(_))
  val configOpts: Opts[NonEmptyList[String]] = Opts
    .arguments[String]("config")
  val configScriptOpts: Opts[Path] = Opts
    .argument[Path]("config-script")
  val configScriptInterpreterOpts: Opts[String] = Opts
    .env[String](
      "RUN_CONFIG_SCRIPT_INTERPRETER",
      "Interpreter for <config-script>."
    )
    .withDefault("python3")
  val configScriptInterpreterArgsOpts: Opts[List[String]] = Opts
    .env[String](
      "RUN_CONFIG_SCRIPT_INTERPRETER_ARGS",
      "Args to be fed to config script interpreter."
    )
    .map(List(_))
    .withDefault(List("-c"))

  val numRunsOpts: Opts[Int] = Opts
    .option[Int](
      "num-runs",
      "Number of runs to create, " +
        "each corresponding to a fresh execution of the config script.",
      "n"
    )
  val fromConfigOpts: Opts[FromConfig] =
    Opts.subcommand(
      "config",
      "Pass the string of arguments given to launch script as in" +
        """
          | ❯ $RUN_LAUNCH_SCRIPT <config>""".stripMargin
    ) {
      configOpts.map(FromConfig)
    }
  val fromConfigScriptOpts: Opts[FromConfigScript] =
    Opts.subcommand(
      "config-script",
      "Use an executable config string to configure runs: " +
        """
          | ❯ for i in `seq <num-runs>`
          | do 
          |   $RUN_LAUNCH_SCRIPT $(<config-script>)
          | done """.stripMargin
    ) {
      (
        configScriptOpts,
        configScriptInterpreterOpts,
        configScriptInterpreterArgsOpts,
        numRunsOpts
      ).mapN(FromConfigScript)
    }
  val dockerFileOpts: Opts[Option[String]] =
    Opts
      .option[String]("file", "The name of the Dockerfile.", short = "f")
      .orNone
  val pathOpts: Opts[String] =
    Opts.argument[String](metavar = "path")

  val newOpts: Opts[New] =
    Opts.subcommand("new", "Launch new runs.") {
      (
        nameOpts,
        descriptionOpts,
        imageOpts,
        imageBuildPathOpts,
        dockerfilePathOpts,
        fromConfigOpts orElse fromConfigScriptOpts
      ).mapN(New)
    }
  val buildOpts: Opts[BuildImage] =
    Opts.subcommand("build", "Builds a docker image!") {
      (dockerFileOpts, pathOpts).mapN(BuildImage)
    }
  val opts: Opts[AllOpts] = (dbPathOpts, newOpts orElse buildOpts).mapN(AllOpts)
}
