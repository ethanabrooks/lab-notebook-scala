package labNotebook

import java.nio.file.{Path, Paths}

import org.rogach.scallop.{ScallopConf, ScallopOption, Subcommand, ValueConverter, singleArgConverter}

import scala.util.Try

class Conf(args: Seq[String]) extends ScallopConf(args) {
  private val pathConverter: ValueConverter[Path] =
    singleArgConverter(Paths.get(_))

  def envPath(s: String): Option[Path] = Some(Paths.get(sys.env(s)))

  val dbPath: ScallopOption[Path] =
    opt(default = envPath("RUN_DB_PATH"))(pathConverter)
  val New = new Subcommand("new") {
    val name: ScallopOption[String] = opt(required = true)
    val configScript: ScallopOption[Path] =
      opt(default = envPath("RUN_CONFIG_SCRIPT"))(pathConverter)
    val numRuns: ScallopOption[Int] = opt()
    val config: ScallopOption[String] = opt()
    mutuallyExclusive(config, configScript)
    mutuallyExclusive(config, numRuns)
    val launchScript: ScallopOption[Path] =
      opt(default = envPath("RUN_LAUNCH_SCRIPT"))(pathConverter)
    val killScript: ScallopOption[Path] =
      opt(default = envPath("RUN_KILL_SCRIPT"))(pathConverter)
    val description: ScallopOption[String] = opt()
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
