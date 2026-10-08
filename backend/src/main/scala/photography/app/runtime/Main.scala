package photography.app.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import fs2.io.net.Network
import org.http4s.ember.server.EmberServerBuilder
import photography.adapters.http.{AlbumRoutes, FrontendRoutes, HealthRoutes}
import photography.adapters.sqlite.SqliteAlbumRepository
import photography.app.album.{AlbumService, UtcClock, UUIDGenerator}
import photography.app.AppConfig

object Main extends IOApp.Simple:

  override def run: IO[Unit] =
    AppConfig.load[IO].flatMap: config =>
      AppResourcesBuilder.make(config).build[IO].use: resources =>
        for
          _ <- resources.flyway.migrate
          albumRepository = SqliteAlbumRepository.make[IO](resources.sqlite)
          albumService    = AlbumService.make(albumRepository, UUIDGenerator.make[IO], UtcClock.make[IO])
          _ <- buildHttpServer(config, albumService).useForever.void
        yield ()

  private[app] def buildHttpServer(config: AppConfig, albumService: AlbumService[IO]) =
    EmberServerBuilder
      .default[IO]
      .withHost(config.http.host)
      .withPort(config.http.port)
      .withHttpApp((HealthRoutes.routes[IO] <+> AlbumRoutes.routes[IO](albumService) <+> FrontendRoutes.routes[IO]).orNotFound)
      .build
