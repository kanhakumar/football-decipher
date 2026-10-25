ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "smi"
ThisBuild / version := "0.1.0-SNAPSHOT"

val catsEffectVersion = "3.7.1"
val fs2Version = "3.14.0"
val http4sVersion = "0.23.38"
val circeVersion = "0.14.17"
val azureIdentityVersion = "1.18.7"

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
      // Azure (ADR-016): Event Hubs bus, Cosmos DB store, managed identity.
      "com.azure" % "azure-messaging-eventhubs" % "5.21.7",
      "com.azure" % "azure-cosmos" % "4.83.0",
      "com.azure" % "azure-identity" % azureIdentityVersion,
      // Sign-in (ADR-018): Entra ID access tokens checked against the identity platform's keys.
      "com.nimbusds" % "nimbus-jose-jwt" % "10.10",
      // Warnings and errors from http4s and the Azure SDK reach stderr (simplelogger.properties).
      "org.slf4j" % "slf4j-simple" % "2.0.20",
      "org.scalameta" %% "munit" % "1.3.6" % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.3.1" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test,
      "com.networknt" % "json-schema-validator" % "3.0.8" % Test
    ),
    // One runnable jar for the container image (core/Dockerfile).
    assembly / mainClass := Some("smi.Main"),
    assembly / assemblyJarName := "app.jar",
    assembly / test := {},
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", "services", _*)                      => MergeStrategy.filterDistinctLines
      case PathList("META-INF", "versions", _, "module-info.class") => MergeStrategy.discard
      case "module-info.class"                                         => MergeStrategy.discard
      case PathList("META-INF", "io.netty.versions.properties")      => MergeStrategy.first
      case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF")) => MergeStrategy.discard
      case x =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    }
  )

addCommandAlias("generate", "runMain smi.generator.Generate")
addCommandAlias("calibrate", "runMain smi.generator.Calibrate")
addCommandAlias("rawfeed", "runMain smi.raw.RawFeed")
addCommandAlias("stats", "runMain smi.Stats")
addCommandAlias("insights", "runMain smi.Insights")
addCommandAlias("workload", "runMain smi.WorkloadCli")
addCommandAlias("publish-match", "runMain smi.Publish")
