package photography.app.runtime

import cats.effect.{IO, IOApp}
import photography.app.{AppConfig, AppResourcesBuilder}

object Main extends IOApp.Simple:

  override def run: IO[Unit] =
    AppConfig.load[IO].flatMap: config =>
      AppResourcesBuilder.make(config).build[IO].use: resources =>
        resources.flyway.migrate.flatMap(_ => IO.never[Unit])
