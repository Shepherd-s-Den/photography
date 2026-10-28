package photography.ports

import cats.data.NonEmptyList

import photography.domain.album.*
import photography.domain.layout.Spread

trait ChapterRepository[F[_]]:
  def findSource(chapter: ChapterId): F[ChapterRepository.SourceResult]

  def create(album: AlbumId, chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId]): F[ChapterRepository.CreateResult]

  def update(command: ChapterRepository.Update): F[ChapterRepository.UpdateResult]

object ChapterRepository:
  enum SourceResult:
    case Found(album: AlbumId, source: PhotoSource)
    case ChapterNotFound, Failed

  final case class Update(
      album: AlbumId,
      chapter: ChapterId,
      destination: DestinationId,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
      period: VisitPeriod,
      sourceFolder: PhotoSource,
      order: NonEmptyList[ChapterId]
  )
  final case class NewChapter(
      id: ChapterId,
      destinationId: DestinationId,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
      period: VisitPeriod,
      description: String,
      sourceFolder: PhotoSource,
      opening: Spread.ChapterStart
  )

  enum CreateResult:
    case Saved
    case AlbumNotFound, UnknownCountries, Failed

  enum UpdateResult:
    case Saved
    case ChapterNotFound, AlbumNotFound, UnknownCountries, Failed
