package photography.app.runtime

import cats.effect.{IO, IOApp}
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.http.HealthRoutes
import photography.app.config.AppConfig
import pureconfig.ConfigSource

object Main extends IOApp.Simple:

  implicit val logger: StructuredLogger[IO] = Slf4jLogger.getLoggerFromName("[Photography]")

  override def run: IO[Unit] =
    AppConfig.load[IO](ConfigSource.defaultApplication).flatMap: config =>
      AppResourcesBuilder.make(config).build[IO].use: resources =>
        for
          _ <- resources.flyway.migrate
          _ <- logger.info(s"Migrations applied; serving on ${config.http.host}:${config.http.port}")
          _ <- buildHttpServer(config).useForever.void
        yield ()

  private[app] def buildHttpServer(config: AppConfig) =
    EmberServerBuilder
      .default[IO]
      .withHost(config.http.host)
      .withPort(config.http.port)
      .withHttpApp(HealthRoutes.routes[IO].orNotFound)
      .build
