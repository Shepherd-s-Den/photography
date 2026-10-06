package photography.app.runtime

import cats.effect.{IO, IOApp}
import photography.app.{AppConfig, PhotographyApplication}

object Main extends IOApp.Simple:

  override def run: IO[Unit] =
    AppConfig.load[IO].flatMap: config =>
      PhotographyApplication.make[IO](config).use(_ => IO.never[Unit])
