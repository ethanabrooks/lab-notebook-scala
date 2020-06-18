package labNotebook

import java.nio.file.{Path, Paths}

import org.rogach.scallop.{ScallopConf, ScallopOption, Subcommand, ValueConverter, singleArgConverter}

import scala.util.Try

class Conf(args: Seq[String]) extends ScallopConf(args) {
  private val pathConverter: ValueConverter[Path] =
    singleArgConverter(Paths.get(_))

  def envPath(s: String): Option[Path] = Some(Paths.get(sys.env(s)))

  val dbPath: ScallopOption[Path] =
    opt(
      default = envPath("RUN_DB_PATH"),
      descr = "Path to database file (driver='com.mysql.jdbc.<this arg>')." +
        "Defaults to env variable RUN_DB_PATH."
    )(pathConverter)
  val New = new Subcommand("new") {
    val name: ScallopOption[String] = opt(
      required = true,
      descr = "Name and primary key of run"
    )
    val configScript: ScallopOption[Path] =
      opt(
        default = envPath("RUN_CONFIG_SCRIPT"),
        descr = "Path to config script, which ... ?" +
          "Defaults to env variable RUN_CONFIG_SCRIPT."
      )(pathConverter)
    val numRuns: ScallopOption[Int] = opt(
      descr = "Number of runs to create. " +
        "Either provide --config-script and --num-runs or just --config."
    )
    val config: ScallopOption[String] = opt(
      descr = "?"
    )
    mutuallyExclusive(config, configScript)
    mutuallyExclusive(config, numRuns)
    val launchScript: ScallopOption[Path] =
      opt(
        default = envPath("RUN_LAUNCH_SCRIPT"),
        descr = "Path to script that launches run. " +
          "Run as $bash <this argument> <arguments produced by config?>" +
          "Defaults to env variable RUN_LAUNCH_SCRIPT."
      )(pathConverter)
    val killScript: ScallopOption[Path] =
      opt(
        default = envPath("RUN_KILL_SCRIPT"),
        descr = "Path to script that kills a run. " +
          "Run as $bash <this argument> <output of run script>" +
          "Defaults to env variable RUN_KILL_SCRIPT."
      )(pathConverter)
    val description: ScallopOption[String] = opt(
      descr = "Optional description of run."
    )
    for (path <- List(launchScript, killScript, configScript))
      validatePathExists(path)
  }
  addSubcommand(New)
  val rm = new Subcommand("rm") {
    val pattern: ScallopOption[String] = opt(required = true)
    val killScript: ScallopOption[Path] = opt(required = true)(pathConverter)
  }
  addSubcommand(rm)
  val lookup = new Subcommand("lookup") {
    val field: ScallopOption[String] =
      opt(
        required = true,
        validate = s => {
          Try {
            classOf[RunRow].getDeclaredField(s)
          }.isSuccess
        }
      )
    val pattern: ScallopOption[String] = opt(required = true)
  }
  addSubcommand(lookup)
  val ls = new Subcommand("ls") {
    val pattern: ScallopOption[String] = opt()
  }
  addSubcommand(ls)
  val reproduce = new Subcommand("reproduce") {
    val pattern: ScallopOption[String] = opt()
  }
  addSubcommand(reproduce)
  verify()
}
