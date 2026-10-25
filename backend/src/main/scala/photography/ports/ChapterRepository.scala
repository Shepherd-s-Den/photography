package photography.ports

import cats.data.NonEmptyList

import photography.domain.album.*

trait ChapterRepository[F[_]]:
  /**
   * Saved commits references, opening and chronological positions together, checking the chapter manager's rules against the same current state.
   * Rejected, AlbumNotFound and Failed leave persisted state unchanged; concurrent writes cannot bypass capacity, Home or folder rules.
   */
  def create(album: AlbumId, chapter: Chapter): F[ChapterRepository.CreateResult]

  /**
   * Uses the same atomic current-state checks as create, preserving the current chapter's identity, description and composition. A missing or foreign
   * target returns ChapterNotFound.
   */
  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[Country],
      period: VisitPeriod,
      sourceFolder: SourceFolder
  ): F[ChapterRepository.UpdateResult]

object ChapterRepository:
  enum CreateResult:
    case Saved
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed

  enum UpdateResult:
    case Saved
    case Rejected(reason: ChapterConflict)
    case ChapterNotFound, AlbumNotFound, Failed
