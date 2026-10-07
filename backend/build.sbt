ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "com.cosminci"
ThisBuild / version      := "0.1.0"

val localPrototypeMode    = sys.env.get("PHOTOGRAPHY_PROTOTYPE_MODE").contains("1")
val localPrototypeSources =
  if (!localPrototypeMode) Seq.empty
  else {
    val marker = file("../.agent-work/active-prototype")
    if (!marker.exists) Seq.empty
    else {
      val slug = IO.read(marker).trim
      if (slug.matches("[a-z0-9]+(?:-[a-z0-9]+)*"))
        Seq(file(s"../.agent-work/$slug/spike/backend").getCanonicalFile)
      else Seq.empty
    }
  }

lazy val root = (project in file("."))
  .settings(
    name := "photography-backend",
    Compile / unmanagedSourceDirectories ++= localPrototypeSources,
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
    ).filterNot(option =>
      localPrototypeMode &&
        (Set(
          "-Werror",
          "-Wunused:all",
          "-Wvalue-discard",
          "-Wnonunit-statement",
          "-Wshadow:all",
          "-Wsafe-init"
        ).contains(option) || option.startsWith("-P:wartremover:traverser:"))
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    wartremoverErrors ++= (if (localPrototypeMode) Seq.empty else Warts.all),
    coverageMinimumStmtTotal   := (if (localPrototypeMode) 0 else 100),
    coverageMinimumBranchTotal := (if (localPrototypeMode) 0 else 100),
    coverageFailOnMinimum      := !localPrototypeMode,
    coverageExcludedPackages   := Seq(
      "photography\\.app\\.runtime\\..*",
      "photography\\.adapters\\.sqlite\\..*",
      ".*photography\\.domain\\..*(AbsoluteLocation|AlbumId|ChapterId|CountryId|DestinationId|PhotoId|RelativeLocation|EditorId|SessionId|CameraId|LensId|CoverPlacementId|CoverSlotId|PhotoSpreadSlotId|SpreadId|SpreadPlacementId|SpreadTemplateId|TextSpreadSlotId).*"
    ).mkString(";"),
    libraryDependencies ++= Seq(
      "org.typelevel"         %% "cats-core"                 % "2.13.0",
      "org.typelevel"         %% "cats-effect"               % "3.6.3",
      "io.github.iltotore"    %% "iron"                      % "3.3.2",
      "io.github.iltotore"    %% "iron-pureconfig"           % "3.0.2",
      "org.typelevel"         %% "doobie-core"               % "1.0.0-RC13",
      "org.typelevel"         %% "doobie-hikari"             % "1.0.0-RC13",
      "com.github.pureconfig" %% "pureconfig-core"           % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-generic-scala3" % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-cats-effect"    % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-http4s"         % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-ip4s"           % "0.17.9",
      "com.github.geirolz"    %% "fly4s"                     % "1.1.1",
      "org.flywaydb"           % "flyway-core"               % "11.18.0",
      "org.xerial"             % "sqlite-jdbc"               % "3.53.4.0",
      "org.http4s"            %% "http4s-dsl"                % "0.23.33",
      "org.http4s"            %% "http4s-ember-server"       % "0.23.33",
      "org.http4s"            %% "http4s-ember-client"       % "0.23.33" % Test,
      "org.typelevel"         %% "munit-cats-effect"         % "2.1.0"   % Test
    ) ++ (if (localPrototypeMode) Seq("io.circe" %% "circe-parser" % "0.14.14") else Seq.empty)
  )
