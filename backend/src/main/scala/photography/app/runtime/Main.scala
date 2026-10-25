package photography.app.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import fs2.io.net.Network
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.http.{AlbumApi, HealthRoutes, SpreadTemplateApi, StaticSite}
import photography.adapters.sqlite.{SqliteAlbumRepository, SqliteSpreadTemplateRepository}
import photography.app.config.AppConfig
import photography.capabilities.{Clock, IdGenerator}
import photography.usecases.{AlbumManager, ChapterManager, SpreadTemplateManager}
import pureconfig.ConfigSource

object Main extends IOApp.Simple:

  implicit val logger: StructuredLogger[IO] = Slf4jLogger.getLoggerFromName("[Photography]")
  private given IdGenerator[IO]             = IdGenerator.make[IO]
  private given Clock[IO]                   = Clock.make[IO]

  override def run: IO[Unit] =
    AppConfig.load[IO](ConfigSource.defaultApplication).flatMap: config =>
      AppResourcesBuilder.make(config).build[IO].use: resources =>
        for
          _ <- resources.flyway.migrate
          _ <- logger.info(s"Migrations applied; serving on ${config.http.host}:${config.http.port}")
          _ <- buildHttpServer(config, buildHttpApp(config, resources)).useForever.void
        yield ()

  private def buildHttpApp(config: AppConfig, resources: AppResourcesBuilder.AppResources[IO]) =
    val templateRepository = SqliteSpreadTemplateRepository.make[IO](resources.sqlite)
    val templates          = SpreadTemplateManager.make(templateRepository)
    val chapterRules       = ChapterManager.rules
    val albumRepository    = SqliteAlbumRepository.make[IO](resources.sqlite, chapterRules)
    val albums             = AlbumManager.make(albumRepository, templates, config.albums.pageSize)
    val api                = HealthRoutes.routes[IO] <+> AlbumApi.make(albums).routes <+> SpreadTemplateApi.make(templates).routes
    StaticSite.make(api, "frontend").app

  private def buildHttpServer(config: AppConfig, app: HttpApp[IO]) =
    EmberServerBuilder
      .default[IO]
      .withHost(config.http.host)
      .withPort(config.http.port)
      .withHttpApp(app)
      .build
