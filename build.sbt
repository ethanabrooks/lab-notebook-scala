name := "lab-notebook"

version := "0.1"

scalaVersion := "2.13.2"

libraryDependencies += "org.rogach" %% "scallop" % "3.4.0"
libraryDependencies += "com.lihaoyi" %% "os-lib" % "0.7.0"
libraryDependencies += "org.typelevel" %% "cats-effect" % "2.1.3"
libraryDependencies ++= Seq(
  "com.typesafe.slick" %% "slick"           % "3.3.2",
  "com.h2database"      % "h2"              % "1.4.200",
  "ch.qos.logback"      % "logback-classic" % "1.2.3"
)


val circeVersion = "0.12.3"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)

libraryDependencies += "io.github.vigoo" %% "prox" % "0.5.1"
libraryDependencies += "dev.profunktor" %% "console4cats" % "0.8.1"

scalacOptions ++= Seq(
  "-explaintypes",
  "-deprecation",
  "-Xcheckinit",
  "-unchecked",
  "-Wvalue-discard",
  "-Wdead-code",
)