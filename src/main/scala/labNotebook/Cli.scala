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

case class LsOpts(pattern: Option[String], active: Boolean) extends SubCommand
case class RmOpts(pattern: Option[String], active: Boolean) extends SubCommand
case class KillOpts(pattern: Option[String]) extends SubCommand
case class ReproduceOpts(pattern: String, active: Boolean) extends SubCommand

trait MainOpts {

  case class AllOpts(dbPath: Path,
                     server: Boolean,
                     yes: Boolean,
                     logDir: Path,
                     sub: SubCommand)

  val dbPathOpts: Opts[Path] =
    Opts
      .env[Path](
        "RUN_DB_PATH",
        "Path to database file (driver='com.mysql.jdbc.<this arg>')."
      )

  val serverOpts: Opts[Boolean] =
    Opts
      .flag(
        "server",
        "Whether to use Server mode. Allows multiple connections but requires running Server " +
          "`java -cp h2*.jar org.h2.tools.Server`"
      )
      .orFalse

  val yesOpts: Opts[Boolean] =
    Opts
      .flag(
        "yes",
        "Whether to use Server mode. Allows multiple connections but requires running Server " +
          "`java -cp h2*.jar org.h2.tools.Server`",
        "y"
      )
      .orFalse

  val logDirOpts: Opts[Path] =
    Opts
      .env[Path](
        "RUN_LOG_DIR",
        "Path to log directory, where volume directories are created."
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

  val requiredPatternOpts: Opts[String] =
    Opts.argument[String]("pattern")

  val patternOpts: Opts[Option[String]] =
    requiredPatternOpts.orNone

  val activeOpts: Opts[Boolean] =
    Opts.flag("active", "Filter for active runs.").orFalse

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

  val lsOpts: Opts[LsOpts] =
    Opts.subcommand("ls", "List runs corresponding to pattern.") {
      (patternOpts, activeOpts).mapN(LsOpts)
    }

  val rmOpts: Opts[RmOpts] =
    Opts.subcommand("rm", "Remove runs corresponding to pattern.") {
      (patternOpts, activeOpts).mapN(RmOpts)
    }

  val killOpts: Opts[KillOpts] =
    Opts.subcommand("kill", "Kill docker containers corresponding to pattern.") {
      patternOpts.map(KillOpts)
    }

  val reproduceOpts: Opts[ReproduceOpts] =
    Opts.subcommand("reproduce", "Reproduce runs corresponding to pattern.") {
      (requiredPatternOpts, activeOpts).mapN(ReproduceOpts)
    }

  val opts: Opts[AllOpts] =
    (
      dbPathOpts,
      serverOpts,
      yesOpts,
      logDirOpts,
      newOpts orElse lsOpts orElse rmOpts orElse killOpts orElse reproduceOpts
    ).mapN(AllOpts)
}
