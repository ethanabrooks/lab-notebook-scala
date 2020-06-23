import sbt.Keys.libraryDependencies

name := "lab-notebook"
version := "0.1"
scalaVersion := "2.13.2"
trapExit := false

libraryDependencies += "org.typelevel" %% "cats-effect" % "2.1.3"
libraryDependencies += "com.monovore" %% "decline-effect" % "1.0.0"
libraryDependencies += "org.tpolecat" %% "doobie-h2" % "0.8.8"
libraryDependencies += "io.github.vigoo" %% "prox" % "0.5.1"
libraryDependencies += "dev.profunktor" %% "console4cats" % "0.8.1"
libraryDependencies += "co.fs2" %% "fs2-io" % "2.4.0"

scalacOptions ++= Seq(
  "-explaintypes",
  "-deprecation",
  "-Xcheckinit",
  "-unchecked",
  "-Wvalue-discard",
  "-Wdead-code"
)
