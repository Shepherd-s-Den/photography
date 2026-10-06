ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.cosminci"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "photography-backend",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-core" % "2.13.0",
      "org.typelevel" %% "cats-effect" % "3.6.3",
      "io.github.iltotore" %% "iron" % "3.3.2",
      "com.github.pureconfig" %% "pureconfig-core" % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-cats-effect" % "0.17.9",
      "com.github.geirolz" %% "fly4s" % "1.1.1",
      "org.flywaydb" % "flyway-core" % "11.18.0",
      "org.xerial" % "sqlite-jdbc" % "3.53.4.0"
    )
  )
