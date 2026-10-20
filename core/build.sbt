ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "smi"
ThisBuild / version := "0.1.0-SNAPSHOT"

val catsEffectVersion = "3.7.1"
val fs2Version = "3.14.0"
val http4sVersion = "0.23.38"
val circeVersion = "0.14.17"

lazy val root = (project in file("."))
  .settings(
    name := "smi-core",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all"),
    Compile / run / fork := true,
    Compile / run / mainClass := Some("smi.Main"),
    // Scala 3 lazy vals still use sun.misc.Unsafe; JDK 25 warns on every forked run.
    Compile / run / javaOptions += "--sun-misc-unsafe-memory-access=allow",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "org.http4s" %% "http4s-circe" % http4sVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "io.circe" %% "circe-yaml-v12" % "1.15.0",
      "org.slf4j" % "slf4j-nop" % "2.0.17",
      "org.scalameta" %% "munit" % "1.3.6" % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.3.1" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test,
      "com.networknt" % "json-schema-validator" % "3.0.8" % Test
    )
  )

addCommandAlias("generate", "runMain smi.generator.Generate")
addCommandAlias("calibrate", "runMain smi.generator.Calibrate")
addCommandAlias("rawfeed", "runMain smi.raw.RawFeed")
addCommandAlias("stats", "runMain smi.Stats")
addCommandAlias("insights", "runMain smi.Insights")
