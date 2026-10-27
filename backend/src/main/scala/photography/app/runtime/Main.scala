package photography.app.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import fs2.io.net.Network
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.http.{AlbumApi, AlbumWorkspaceApi, HealthRoutes, SpreadTemplateApi, StaticSite}
import photography.app.config.{AppConfig, HttpServerConfig}
import photography.capabilities.{Clock, IdGenerator}
import pureconfig.ConfigSource

object Main extends IOApp.Simple:

  implicit val logger: StructuredLogger[IO] = Slf4jLogger.getLoggerFromName("[Photography]")
  private given IdGenerator[IO]             = IdGenerator.make[IO]
  private given Clock[IO]                   = Clock.make[IO]

  override def run: IO[Unit] =
    AppConfig.load[IO](ConfigSource.defaultApplication).flatMap: config =>
      AppResourcesBuilder.make(config.sqlite).build[IO].use: resources =>
        val serve = AppManagers.make[IO](resources.sqlite, config.albums.pageSize, config.albums.photoRoot).use: managers =>
          buildHttpServer(config.http, buildHttpApp(managers)).useForever.void
        for
          _ <- resources.flyway.migrate
          _ <- logger.info(s"Migrations applied; serving on ${config.http.host}:${config.http.port}")
          _ <- serve
        yield ()

  private def buildHttpApp(managers: AppManagers[IO]) =
    val metadata  = AlbumApi.make(managers.albums).routes
    val workspace = AlbumWorkspaceApi.make(managers.workspaces, managers.albums, managers.chapters).routes
    val api       = HealthRoutes.routes[IO] <+> metadata <+> workspace <+> SpreadTemplateApi.make(managers.templates).routes
    StaticSite.make(api, "frontend").app

  private def buildHttpServer(config: HttpServerConfig, app: HttpApp[IO]) =
    EmberServerBuilder.default[IO].withHost(config.host).withPort(config.port).withHttpApp(app).build
