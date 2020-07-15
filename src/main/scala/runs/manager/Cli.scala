package runs.manager

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

case class NewOpts(name: String,
                   description: Option[String],
                   image: String,
                   imageBuildPath: Path,
                   DockerfilePath: Path,
                   newMethod: NewMethod)
    extends SubCommand

case class LsOpts(pattern: Option[String], active: Boolean) extends SubCommand
case class RmOpts(pattern: Option[String], active: Boolean) extends SubCommand
case class MvOpts(pattern: String,
                  active: Boolean,
                  regex: Option[String],
                  replace: String)
    extends SubCommand
case class KillOpts(pattern: Option[String], active: Boolean) extends SubCommand

case class ReproduceOpts(name: Option[String],
                         pattern: String,
                         active: Boolean,
                         description: Option[String],
                         resample: Boolean,
                         configScriptInterpreter: String,
                         configScriptInterpreterArgs: List[String])
    extends SubCommand

case class LookupOpts(pattern: Option[String], active: Boolean, field: String)
    extends SubCommand

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
          | ❯ docker run -d --rm -it <image> <config>""".stripMargin
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
          |   docker run -d --rm -it <image> $(<config-script>)
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

  val regexOpts: Opts[Option[String]] =
    Opts.option[String]("regex", "Regex pattern to use for replacement.").orNone

  val replaceOpts: Opts[String] =
    Opts.argument[String]("replace")

  val fieldOpts: Opts[String] =
    Opts
      .argument[String]("field")
      .validate(
        "must be one of the following choices:\n" ++ RunRow.fields
          .mkString("\n")
      )(RunRow.fields.contains(_))

  val activeOpts: Opts[Boolean] =
    Opts.flag("active", "Filter for active runs.").orFalse

  val resampleOpts: Opts[Boolean] =
    Opts
      .flag("resample", "Resample configs from configScripts when possible.")
      .orFalse

  val newOpts: Opts[NewOpts] =
    Opts.subcommand("new", "Launch new runs.") {
      (
        nameOpts,
        descriptionOpts,
        imageOpts,
        imageBuildPathOpts,
        dockerfilePathOpts,
        fromConfigOpts orElse fromConfigScriptOpts
      ).mapN(NewOpts)
    }

  val lsOpts: Opts[LsOpts] =
    Opts.subcommand("ls", "List runs corresponding to pattern.") {
      (patternOpts, activeOpts).mapN(LsOpts)
    }

  val rmOpts: Opts[RmOpts] =
    Opts.subcommand("rm", "Remove runs corresponding to pattern.") {
      (patternOpts, activeOpts).mapN(RmOpts)
    }

  val mvOpts: Opts[MvOpts] =
    Opts.subcommand(
      "mv",
      "Rename runs corresponding to pattern. " +
        "If --regex is provided, uses the REGEXP_REPLACE H2 command to modify names." +
        "Otherwise uses REPLACE H2 command with <pattern> as search string."
    ) {
      (requiredPatternOpts, activeOpts, regexOpts, replaceOpts).mapN(MvOpts)
    }

  val killOpts: Opts[KillOpts] =
    Opts.subcommand("kill", "Kill docker containers corresponding to pattern.") {
      (patternOpts, activeOpts).mapN(KillOpts)
    }

  val lookupOpts: Opts[LookupOpts] =
    Opts.subcommand(
      "lookup",
      "Kill docker containers corresponding to pattern."
    ) {
      (patternOpts, activeOpts, fieldOpts).mapN(LookupOpts)
    }

  val reproduceOpts: Opts[ReproduceOpts] =
    Opts.subcommand("reproduce", "Reproduce runs corresponding to pattern.") {
      (
        nameOpts.orNone,
        requiredPatternOpts,
        activeOpts,
        descriptionOpts,
        resampleOpts,
        configScriptInterpreterOpts,
        configScriptInterpreterArgsOpts,
      ).mapN(ReproduceOpts)
    }

  val opts: Opts[AllOpts] =
    (
      dbPathOpts,
      serverOpts,
      yesOpts,
      logDirOpts,
      newOpts
        orElse lsOpts
        orElse lookupOpts
        orElse rmOpts
        orElse mvOpts
        orElse killOpts
        orElse reproduceOpts
    ).mapN(AllOpts)
}
