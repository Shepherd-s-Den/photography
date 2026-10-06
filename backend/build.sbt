ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.cosminci"
ThisBuild / version      := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "photography-backend",
    scalacOptions ++= Seq(
      "-encoding",
      "utf8",
      "-feature",
      "-deprecation",
      "-unchecked",
      "-Werror",
      "-Wunused:all",
      "-Wvalue-discard",
      "-Wnonunit-statement",
      "-Wshadow:all",
      "-Wsafe-init",
      "-java-output-version",
      "21"
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    wartremoverErrors ++= Warts.all,
    libraryDependencies ++= Seq(
      "org.typelevel"         %% "cats-core"              % "2.13.0",
      "org.typelevel"         %% "cats-effect"            % "3.6.3",
      "io.github.iltotore"    %% "iron"                   % "3.3.2",
      "org.typelevel"         %% "doobie-core"            % "1.0.0-RC13",
      "org.typelevel"         %% "doobie-hikari"          % "1.0.0-RC13",
      "com.github.pureconfig" %% "pureconfig-core"        % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-cats-effect" % "0.17.9",
      "com.github.geirolz"    %% "fly4s"                  % "1.1.1",
      "org.flywaydb"           % "flyway-core"            % "11.18.0",
      "org.xerial"             % "sqlite-jdbc"            % "3.53.4.0"
    )
  )
