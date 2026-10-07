package photography.app.runtime

import cats.effect.{IO, IOApp}
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import photography.adapters.http.HealthRoutes
import photography.app.AppConfig

object Main extends IOApp.Simple:

  override def run: IO[Unit] =
    AppConfig.load[IO].flatMap: config =>
      AppResourcesBuilder.make(config).build[IO].use: resources =>
        for
          _ <- resources.flyway.migrate
          _ <- buildHttpServer(config).useForever.void
        yield ()

  private[app] def buildHttpServer(config: AppConfig) =
    EmberServerBuilder
      .default[IO]
      .withHost(config.http.host)
      .withPort(config.http.port)
      .withHttpApp(HealthRoutes.routes[IO].orNotFound)
      .build
