package photography.app.runtime

import cats.effect.Async
import cats.effect.std.Mutex
import cats.syntax.all.*
import org.typelevel.doobie.Transactor
import org.typelevel.log4cats.Logger
import photography.adapters.sqlite.{SqliteAlbumRepository, SqliteChapterRepository, SqliteCountryRepository, SqliteSpreadTemplateRepository}
import photography.capabilities.{Clock, IdGenerator}
import photography.domain.album.PhotoSource
import photography.usecases.{AlbumManager, AlbumPageSize, AlbumWorkspaceManager, ChapterManager, SpreadTemplateManager}

final case class AppManagers[F[_]](
    albums: AlbumManager[F],
    chapters: ChapterManager[F],
    templates: SpreadTemplateManager[F],
    workspaces: AlbumWorkspaceManager[F]
)

object AppManagers:
  def make[F[_]: Async: Logger: Clock: IdGenerator](
      sqlite: Transactor[F],
      pageSize: AlbumPageSize,
      photoRoot: PhotoSource
  ): F[AppManagers[F]] =
    Mutex[F].map: mutex =>
      given Mutex[F]         = mutex
      val templateRepository = SqliteSpreadTemplateRepository.make[F](sqlite)
      val templates          = SpreadTemplateManager.make(templateRepository)
      val albumRepository    = SqliteAlbumRepository.make[F](sqlite)
      val chapterRepository  = SqliteChapterRepository.make[F](sqlite)
      val countries          = SqliteCountryRepository.make[F](sqlite)
      val albums             = AlbumManager.make(albumRepository, templates, pageSize)
      val chapters           = ChapterManager.make(albumRepository, chapterRepository, templates, countries)
      val workspaces         = AlbumWorkspaceManager.make(albums, countries, photoRoot)
      AppManagers(albums, chapters, templates, workspaces)
