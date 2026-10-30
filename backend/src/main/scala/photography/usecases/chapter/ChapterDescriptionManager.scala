package photography.usecases.chapter

import cats.Functor
import cats.syntax.all.*
import photography.domain.album.{AlbumId, ChapterId}
import photography.ports.chapter.ChapterDescriptionRepository
import photography.usecases.chapter.ChapterDescriptionManager.*

trait ChapterDescriptionManager[F[_]]:
  def read(album: AlbumId, chapter: ChapterId): F[ReadResult]
  def save(album: AlbumId, chapter: ChapterId, text: String): F[SaveResult]

object ChapterDescriptionManager:
  def make[F[_]: Functor](repository: ChapterDescriptionRepository[F]): ChapterDescriptionManager[F] = new LiveChapterDescriptionManager(repository)

  final private class LiveChapterDescriptionManager[F[_]: Functor](repository: ChapterDescriptionRepository[F]) extends ChapterDescriptionManager[F]:
    override def read(album: AlbumId, chapter: ChapterId): F[ReadResult] =
      repository.read(album, chapter).map:
        case ChapterDescriptionRepository.ReadResult.Found(text) => ReadResult.Found(text)
        case ChapterDescriptionRepository.ReadResult.NotFound    => ReadResult.NotFound
        case ChapterDescriptionRepository.ReadResult.Failed      => ReadResult.Failed

    override def save(album: AlbumId, chapter: ChapterId, text: String): F[SaveResult] =
      repository.save(album, chapter, text).map:
        case ChapterDescriptionRepository.WriteResult.Saved     => SaveResult.Saved
        case ChapterDescriptionRepository.WriteResult.NotFound  => SaveResult.NotFound
        case ChapterDescriptionRepository.WriteResult.Failed    => SaveResult.Failed
        case ChapterDescriptionRepository.WriteResult.Uncertain => SaveResult.Uncertain

  enum ReadResult derives CanEqual:
    case Found(text: String)
    case NotFound, Failed

  enum SaveResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain
