package runs.manager

import java.nio.file.Paths

import cats.effect.Console.io.{putStrLn, readLn}
import cats.effect.{Blocker, ExitCode, IO}
import cats.implicits._
import doobie.h2.H2Transactor
import doobie.implicits._
import runs.manager.Main._
import doobie.{ConnectionIO, ExecutionContexts, Fragments}

import scala.language.postfixOps

trait MvCommand {
  def mvCommand(pattern: String,
                active: Boolean,
                regex: Option[String],
                replace: String)(implicit blocker: Blocker,
                                 xa: H2Transactor[IO],
                                 yes: Boolean): IO[ExitCode] = {
    for {
      conditions <- selectConditions(Some(pattern), active)
      results <- essentialDataQuery(conditions).transact(xa)
      _ <- {
        val names = results.map(_.name)
        val conditions = Fragments
          .or(names.map(name => fr"name = $name").toIndexedSeq: _*)
        val merge =
          fr"""MERGE INTO runs AS T USING (SELECT * FROM runs where """ ++ conditions ++
            fr""") AS S
              |    ON T.name = S.name
              |    WHEN MATCHED THEN
              |        UPDATE SET T.NAME = """.stripMargin ++
            regex.fold(fr"REPLACE(T.Name, $pattern, $replace)")(
              regex => fr"REGEXP_REPLACE(T.Name, $regex, $replace)"
            )
        ""

        putStrLn(if (yes) {
          "Updating the following runs:"
        } else {
          "Update the following runs?"
        }) >>
          names.traverse(putStrLn) >> pause >>
          merge.update.run.transact(xa)
      }
    } yield ExitCode.Success
  }
}
