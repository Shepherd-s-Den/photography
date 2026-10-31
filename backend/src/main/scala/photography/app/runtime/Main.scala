package photography.app.runtime

import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import fs2.io.net.Network
import org.http4s.HttpApp
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.StructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits.*
import photography.adapters.http.{AlbumApi, ChapterApi, CoverApi, HealthRoutes, SpreadApi, SpreadTemplateApi, StaticSite}
import photography.adapters.filesystem.FileThumbnailStorage
import photography.adapters.http.{GearApi, ThumbnailApi}
import photography.adapters.sqlite.photo.SqlitePhotoRepository
import photography.app.config.{AppConfig, HttpServerConfig}
import photography.app.runtime.AppResourcesBuilder.AppResources
import photography.capabilities.{Clock, IdGenerator, Transaction}
import photography.usecases.photo.PhotoWriteRecovery
import photography.ports.photo.ThumbnailStorage
import scala.concurrent.duration.*

object Main extends IOApp.Simple:

  implicit val logger: StructuredLogger[IO] = Slf4jLogger.getLoggerFromName("[Photography]")
  private given IdGenerator[IO]             = IdGenerator.make[IO]
  private given Clock[IO]                   = Clock.make[IO]

  override def run: IO[Unit] =
    AppConfig.make.flatMap: config =>
      AppResourcesBuilder.make(config.sqlite).build[IO].use: resources =>
        resources.flyway.migrate >> runPrograms(config, resources)

  private def runPrograms(config: AppConfig, resources: AppResources[IO]) =
    given Transaction[ConnectionIO, IO] with
      override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(resources.sqlite)
      override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(resources.sqlite)
    val photos     = SqlitePhotoRepository.make
    val thumbnails = FileThumbnailStorage.make[IO](config.thumbnails.root)
    val recovery   = PhotoWriteRecovery.make(photos, thumbnails)
    val recover    = recovery.recover.flatMap:
      case PhotoWriteRecovery.RecoveryResult.Recovered       => IO.unit
      case failure: PhotoWriteRecovery.RecoveryResult.Failed =>
        IO.raiseError[Unit](new IllegalStateException(s"Photo write recovery failed: ${failure.issues}"))
    val builder = ProgramsBuilder.make[IO](resources.sqlite, config.albums, photos, thumbnails, config.thumbnails.quality)
    val serve   = builder.build.use: programs =>
      val server = buildHttpApp(programs, thumbnails).flatMap(httpApp => buildHttpServer(config.http, httpApp))
      server.useForever.void
    for
      _ <- recover
      _ <- logger.info(s"Migrations applied; serving on ${config.http.host}:${config.http.port}")
      _ <- serve
    yield ()

  private def buildHttpApp(programs: Programs[IO], thumbnails: ThumbnailStorage[IO]) =
    val albums       = AlbumApi.make(programs.albums, programs.workspaces, programs.photoEvents).routes
    val chapters     = ChapterApi.make(programs.workspaces, programs.albums, programs.chapters, programs.chapterGear).routes
    val spreads      = SpreadApi.make(programs.spreads).routes
    val covers       = CoverApi.make(programs.coverPlacements).routes
    val thumbnailApi = ThumbnailApi.make(thumbnails).routes
    val gear         = GearApi.make(programs.gear).routes
    val templates    = SpreadTemplateApi.make(programs.templates).routes
    val api          = HealthRoutes.routes[IO] <+> albums <+> chapters <+> spreads <+> covers <+> thumbnailApi <+> templates <+> gear
    StaticSite.make(api, assetRoot = "frontend").map(_.app)

  private def buildHttpServer(config: HttpServerConfig, app: HttpApp[IO]) =
    EmberServerBuilder.default[IO].withHost(config.host).withPort(config.port).withIdleTimeout(60.seconds).withHttpApp(app).build
