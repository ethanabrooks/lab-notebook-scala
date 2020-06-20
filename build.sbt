import sbt.Keys.libraryDependencies

name := "lab-notebook"
version := "0.1"
scalaVersion := "2.13.2"
trapExit := false

libraryDependencies += "org.typelevel" %% "cats-effect" % "2.1.3"
libraryDependencies += "com.monovore" %% "decline" % "1.0.0"
libraryDependencies += "com.monovore" %% "decline-effect" % "1.0.0"
libraryDependencies += "org.apache.logging.log4j" % "log4j-api" % "2.13.3"
libraryDependencies += "org.apache.logging.log4j" % "log4j-core" % "2.13.3"
lazy val doobieVersion = "0.8.8"

libraryDependencies ++= Seq(
  "org.tpolecat" %% "doobie-core"     % doobieVersion,
  "org.tpolecat" %% "doobie-postgres" % doobieVersion,
  "org.tpolecat" %% "doobie-specs2"   % doobieVersion,
  "org.tpolecat" %% "doobie-h2"       % doobieVersion
)


val circeVersion = "0.12.3"

libraryDependencies += "io.github.vigoo" %% "prox" % "0.5.1"
libraryDependencies += "dev.profunktor" %% "console4cats" % "0.8.1"
libraryDependencies += "co.fs2" %% "fs2-core" % "2.4.0" // For cats 2 and cats-effect 2
libraryDependencies += "co.fs2" %% "fs2-io" % "2.4.0"

scalacOptions ++= Seq(
  "-explaintypes",
  "-deprecation",
  "-Xcheckinit",
  "-unchecked",
  "-Wvalue-discard",
  "-Wdead-code",
)
