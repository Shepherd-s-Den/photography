ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.cosminci"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "photography-backend",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "io.github.iltotore" %% "iron" % "3.3.2"
    )
  )
