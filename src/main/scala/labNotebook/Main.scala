package labNotebook

import cats.effect.Console.io.putStrLn
import cats.effect.{ExitCode, IO}
import com.monovore.decline._
import com.monovore.decline.effect._
import labNotebook.Main.newCommand

import scala.language.postfixOps

object Main
    extends CommandIOApp(
      name = "run-manager",
      header = "Manages long-running processes (runs).",
    )
    with LabNotebookOpts
    with NewCommand {

  override def main: Opts[IO[ExitCode]] = opts.map {
    case AllOpts(dbPath, sub) =>
      sub match {
        case New(
            name,
            description,
            launchScript,
            killScript,
            newMethod: NewMethod
            ) =>
          for {
            _ <- putStrLn("HERE 0")
            x <- newCommand(
              name = name,
              description = description,
              launchScriptPath = launchScript,
              killScriptPath = killScript,
              newMethod = newMethod
            )

          } yield x
        case BuildImage(dockerFile, path) =>
          putStrLn(s"build,$dbPath dockerfile: $dockerFile path: $path") as ExitCode.Success
      }
  }

  //  private val logger = LogManager.getLogger(Main.getClass);

  //  def lookupRuns(field: String, pattern: String)(
  //    implicit dbPath: Path): IO[ExitCode] = {
  //    for {
  //
  //      ids <- DB.connect(dbPath).use { db =>
  //        val query = table
  //          .filter(_.name like pattern)
  //          .map { row => row.name
  //            //            field match {
  //            //              case "commit" => row.commit
  //            //              case "config" => row.config
  //            //              case "configScript" => row.configScript
  //            //              case "containerId" => row.containerId
  //            //              case "description" => row.description
  //            //              case "killScript" => row.killScript
  //            //              case "launchScript" => row.launchScript
  //            //              case _ => row.name
  //            //            }
  //          }
  //        IO.fromFuture(IO(db.run(query.result)))
  //      }
  //      _ <- if (ids.isEmpty) {
  //        putStrLn(s"No runs found.")
  //      } else {
  //        ids.toList.traverse(putStrLn)
  //      }
  //    } yield ExitCode.Success
  //  }
  //
  //  def lsAllRuns(field: String)(
  //    implicit dbPath: Path): IO[ExitCode] = {
  //    for {
  //      ids <- DB.connect(dbPath).use { db =>
  //        val query = table
  //          .map(row => row.name)
  //        //          .map(_.stringToField(field))
  //        IO.fromFuture(IO(db.run(query.result)))
  //      }
  //      _ <- if (ids.isEmpty) {
  //        putStrLn(s"No runs found.")
  //      } else {
  //        ids.toList.traverse(putStrLn)
  //      }
  //    } yield ExitCode.Success
  //  }

  //  def reproduceRuns(pattern: String)(
  //    implicit dbPath: Path): IO[ExitCode] = {
  //    for {
  //      triples <- DB.connect(dbPath).use { db =>
  //        db.execute(
  //          table
  //            .filter(_.name like pattern)
  //            .map((e: RunTable) => {
  //              (e.commit, e.launchScript, e.config)
  //            }).result)
  //      }
  //      _ <- if (triples.isEmpty) {
  //        putStrLn(s"No runs match pattern $pattern")
  //      } else {
  //        triples.toList.traverse {
  //          case (commit, launchScript, config) =>
  //            putStrLn(s"git checkout $commit") >>
  //              putStrLn(s"""eval '$launchScript' $config""")
  //        }
  //      }
  //    } yield ExitCode.Success
  //  }

  //  def relaunchRuns(pattern: String, name: String, numRuns: Int,
  //                  )(
  //                    implicit dbPath: Path): IO[ExitCode] =
  //    for {
  //      configScripts <- DB.connect(dbPath).use { db =>
  //        db.execute(
  //          table
  //            .filter(_.name like pattern)
  //            .map(r => (r.configScript, r.launchScript, r.killScript)).result)
  //      }
  //
  //      _ <- Blocker[IO].use { blocker =>
  //        implicit val blocker: Blocker = blocker
  //        configScripts.iterator.collectFirst({ case (Some(configScript), launchScript, killScript) =>
  //          val configMap = ConfigMap.build(configScript = Paths.get(configScript),
  //            numRuns = numRuns, name = name)
  //          (configMap, launchScript, killScript)
  //        }) match {
  //          case None => IO.unit
  //          case Some((configMap, launchScript, killScript)) => {
  //            for {
  //              configMap <- configMap
  //              r <- newRuns(
  //              configMap = configMap,
  //              killProc = stringToProc(killScript),
  //              launchRuns = launchRuns(launchScript = launchScript, configMap=configMap)(blocker),
  //              inserNewRuns = insertNewRuns(
  //                launchProc, killScript
  //              ),
  //            )
  //            } yield r
  //          }
  //        }
  //      }
  //    } yield ExitCode.Success

  //      _ <- if (configScripts.isEmpty) {
  //        putStrLn(s"No runs match pattern $pattern")
  //      } else {
  //        for {
  //
  //        } yield
  //      }

  //  def rmRuns(pattern: String, killProc: List[String] => Process[IO, _, _])(
  //    implicit dbPath: Path): IO[ExitCode] = {
  //    DB.connect(dbPath).use { db =>
  //      val query = table.filter(_.name like pattern)
  //      db.execute(query.result) >>= { (matches: Seq[RunRow]) =>
  //        val ids = matches.map(_.containerId).toList
  //        if (matches.isEmpty) {
  //          putStrLn(s"No runs match pattern $pattern")
  //        } else {
  //          putStrLn("Delete the following rows?") >>
  //            matches.map(_.name).toList.traverse(putStrLn) >>
  //            readLn >>
  //            Blocker[IO].use(killProc(ids).run(_)) >>
  //            db.execute(query.delete)
  //        }
  //      }
  //    } >> IO.pure(ExitCode.Success)
  //
  //  }
}

//object Main extends CommandApp {
//
//
////  override def run(args: List[String]): IO[ExitCode] = {
////    implicit val dbPath: Path = conf.dbPath()
////    return IO(ExitCode.Success)
//
//    conf.subcommand match {
//      case Some(conf.New) =>
//        val name = conf.New.name()
//        val launchScriptPath: Path = conf.New.launchScript()
//        Blocker[IO].use(b => {
//          implicit val blocker: Blocker = b
//          for {
//            configMap <- (conf.New.config.toOption,
//              conf.New.configScript.toOption,
//              conf.New.numRuns.toOption) match {
//              case (Some(config), _, None) =>
//                IO.pure(Map(name -> config))
//              case (None, Some(configScript), Some(numRuns)) =>
//                ConfigMap.build(configScript = configScript,
//                  numRuns = numRuns,
//                  name = name)
//              case _ =>
//                IO.raiseError(new RuntimeException(
//                  "--config and --num-runs are mutually exclusive argument groups."))
//            }
//            _ <- putStrLn("Create the following runs?") >>
//              configMap.print() >>
//              readLn
//            commit <- getCommit
//            description <- getDescription(conf.New.description.toOption)
//            configScript <- readMaybePath(conf.New.configScript.toOption)
//            launchScript <- readPath(launchScriptPath)
//            killScript <- readPath(conf.New.killScript())
//            result <- newRuns(
//              configMap = configMap,
//              killProc = killProc(conf.New.killScript()),
//              launchRuns = launchRuns(configMap, launchProc(launchScriptPath)),
//              insertNewRuns = insertNewRuns(
//                launchScript = launchScript,
//                killScript = killScript,
//                commit = commit,
//                description = description,
//                configScript = configScript,
//                configMap = configMap,
//              )
//            )
//          } yield result
//        })
//
//      //      case Some(conf.lookup) =>
//      //        lookupRuns(field = conf.lookup.field(), pattern = conf.lookup.pattern())
//      //      case Some(conf.rm) =>
//      //        rmRuns(killProc = killProc(conf.rm.killScript()),
//      //          pattern = conf.rm.pattern())
//      //      case Some(conf.ls) =>
//      //        lookupRuns(field = "name", pattern = conf.ls.pattern.toOption.getOrElse("%"))
//      //      case Some(conf.reproduce) => {
//      //        IO(ExitCode.Success)
//      //      }
//      case x => IO.raiseError(new RuntimeException(s"Don't know how to handle argument $x"))
//    }
//  }
//}
