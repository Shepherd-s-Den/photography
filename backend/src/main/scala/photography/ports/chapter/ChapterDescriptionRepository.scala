package photography.ports.chapter

import photography.domain.album.{AlbumId, ChapterId}

/** The chapter must belong to the album; writes atomically change only description. Uncertain outcomes require reconciliation. */
trait ChapterDescriptionRepository[F[_]]:
  def read(album: AlbumId, chapter: ChapterId): F[ChapterDescriptionRepository.ReadResult]
  def save(album: AlbumId, chapter: ChapterId, text: String): F[ChapterDescriptionRepository.WriteResult]

object ChapterDescriptionRepository:
  enum ReadResult derives CanEqual:
    case Found(text: String)
    case NotFound, Failed

  enum WriteResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain
