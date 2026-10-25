package photography.ports

import cats.data.NonEmptyList

import photography.domain.album.*

trait ChapterRepository[F[_]]:
  /**
   * Commits the chapter, references, opening and supplied order together. Unsuccessful writes leave persisted state unchanged. The manager holds the
   * shared mutation mutex from its album read through this write; implementations do not choose chapter policy or order.
   */
  def create(album: AlbumId, chapter: Chapter, order: NonEmptyList[ChapterId]): F[ChapterRepository.CreateResult]

  /**
   * Commits editable metadata and supplied order together, preserving identity, description and composition. A missing or foreign target returns
   * ChapterNotFound. The same mutation mutex covers the manager's read and this write.
   */
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
