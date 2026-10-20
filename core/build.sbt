ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "smi"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = (project in file("."))
  .settings(
    name := "smi-core",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Wunused:all"),
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "1.0.2" % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test,
      "org.scalacheck" %% "scalacheck" % "1.18.1" % Test
    )
  )
