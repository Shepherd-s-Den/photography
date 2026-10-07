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
    coverageMinimumStmtTotal   := 100,
    coverageMinimumBranchTotal := 100,
    coverageFailOnMinimum      := true,
    coverageExcludedPackages   := Seq(
      "photography\\.app\\.runtime\\..*",
      "photography\\.adapters\\.sqlite\\..*",
      ".*photography\\.domain\\..*(AbsoluteLocation|AlbumId|ChapterId|CountryId|DestinationId|PhotoId|RelativeLocation|EditorId|SessionId|CameraId|LensId|CoverPlacementId|CoverSlotId|PhotoSpreadSlotId|SpreadId|SpreadPlacementId|SpreadTemplateId|TextSpreadSlotId).*"
    ).mkString(";"),
    libraryDependencies ++= Seq(
      "org.typelevel"         %% "cats-core"                 % "2.13.0",
      "org.typelevel"         %% "cats-effect"               % "3.7.1",
      "io.github.iltotore"    %% "iron"                      % "3.3.2",
      "io.github.iltotore"    %% "iron-pureconfig"           % "3.0.2",
      "org.typelevel"         %% "doobie-core"               % "1.0.0-RC13",
      "org.typelevel"         %% "doobie-hikari"             % "1.0.0-RC13",
      "com.github.pureconfig" %% "pureconfig-core"           % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-cats-effect"    % "0.17.10",
      "com.github.pureconfig" %% "pureconfig-http4s"         % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-ip4s"           % "0.17.9",
      "com.github.geirolz"    %% "fly4s"                     % "1.1.1",
      "org.flywaydb"           % "flyway-core"               % "13.9.0",
      "org.xerial"             % "sqlite-jdbc"               % "3.53.4.0",
      "org.http4s"            %% "http4s-dsl"                % "0.23.33",
      "org.http4s"            %% "http4s-ember-server"       % "0.23.33",
      "org.http4s"            %% "http4s-ember-client"       % "0.23.33" % Test,
      "org.typelevel"         %% "munit-cats-effect"         % "2.1.0"   % Test
    )
  )
