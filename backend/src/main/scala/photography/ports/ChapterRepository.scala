package photography.ports

import cats.data.NonEmptyList

import photography.domain.album.*

trait ChapterRepository[F[_]]:
  // Saves atomically persist the supplied metadata, references, opening and complete album order; failures leave all rows unchanged.
  // Order membership is checked within the write transaction. Callers coordinate business decisions made before the write.
  def create(album: AlbumId, chapter: Chapter, order: NonEmptyList[ChapterId]): F[ChapterRepository.CreateResult]

  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[Country],
      period: VisitPeriod,
      sourceFolder: SourceFolder,
      order: NonEmptyList[ChapterId]
  ): F[ChapterRepository.UpdateResult]

object ChapterRepository:
  enum CreateResult:
    case Saved
    case AlbumNotFound, Failed

  enum UpdateResult:
    case Saved
    case ChapterNotFound, AlbumNotFound, Failed
