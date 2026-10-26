package photography.ports

import cats.data.NonEmptyList

import photography.domain.album.*

trait ChapterRepository[F[_]]:
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
