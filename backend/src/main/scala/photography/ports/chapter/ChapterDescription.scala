package photography.ports.chapter

import photography.domain.album.{AlbumId, ChapterId}

/**
 * Application operations require the chapter to belong to the album. Saves atomically change only description; uncertainty requires reconciliation.
 */
trait ChapterDescription[F[_]]:
  def read(album: AlbumId, chapter: ChapterId): F[ChapterDescription.ReadResult]
  def save(album: AlbumId, chapter: ChapterId, text: String): F[ChapterDescription.SaveResult]

object ChapterDescription:
  enum ReadResult derives CanEqual:
    case Found(text: String)
    case NotFound, Failed

  enum SaveResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain
