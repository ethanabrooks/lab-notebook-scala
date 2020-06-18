package labNotebook
import java.nio.file.Path
import cats.implicits._

import com.monovore.decline.Opts
import labNotebook.Main.{
  configOpts,
  configScriptOpts,
  dbPathOpts,
  descriptionOpts,
  dockerFileOpts,
  fromConfigOpts,
  killOpts,
  launchScriptOpts,
  nameOpts,
  newOpts,
  numRunsOpts,
  pathOpts
}

trait CommandOpts {
  abstract class NewMethod
  case class FromConfig(config: String) extends NewMethod
  case class FromConfigScript(configScript: Path, numRuns: Int)
      extends NewMethod

  abstract class SubCommand

  case class New(name: String,
                 description: Option[String],
                 launchScript: Path,
                 killScript: Path,
                 newMethod: NewMethod)
      extends SubCommand

  case class BuildImage(dockerFile: Option[String], path: String)
      extends SubCommand

  case class AllOpts(dbPath: Path, sub: SubCommand)
  val dbPathOpts: Opts[Path] =
    Opts.env[Path](
      "RUN_DB_PATH",
      """Path to database file (driver='com.mysql.jdbc.<this arg>'). 
        |Defaults to env variable RUN_DB_PATH.""".stripMargin,
    )
  val nameOpts: Opts[String] = Opts
    .option[String]("name", "Name and primary key of run", short = "n")
  val descriptionOpts: Opts[Option[String]] = Opts
    .option[String]("description", "Optional description of run", short = "d")
    .orNone
  val launchScriptOpts: Opts[Path] = Opts
    .env[Path](
      "RUN_LAUNCH_SCRIPT",
      """Path to script that launches run.
        |Run as $bash <this argument> <arguments produced by config?>""".stripMargin
    )
  val killOpts: Opts[Path] = Opts
    .env[Path](
      "RUN_KILL_SCRIPT",
      """Path to script that kills a run.
        |Run as $bash <this argument> <output of run script>.""".stripMargin
    )
  val configOpts: Opts[String] = Opts
    .argument[String]("config")
  val configScriptOpts: Opts[Path] = Opts
    .argument[Path]("config-script")
  val numRunsOpts: Opts[Int] = Opts
    .option[Int]("num-runs", "Number of runs to create.", short = "nr")
  val fromConfigOpts: Opts[FromConfig] =
    Opts.subcommand("config", "use a config string to configure runs") {
      configOpts.map(FromConfig)
    }
  val fromConfigScriptOpts: Opts[FromConfigScript] =
    Opts.subcommand("config-script", "use a config script to configure runs") {
      (configScriptOpts, numRunsOpts).mapN(FromConfigScript)
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
        launchScriptOpts,
        killOpts,
        fromConfigOpts orElse fromConfigScriptOpts
      ).mapN(New)
    }
  val buildOpts: Opts[BuildImage] =
    Opts.subcommand("build", "Builds a docker image!") {
      (dockerFileOpts, pathOpts).mapN(BuildImage)
    }
  val opts: Opts[AllOpts] = (dbPathOpts, newOpts orElse buildOpts).mapN(AllOpts)
}
