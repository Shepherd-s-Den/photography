package photography.ports

import cats.data.NonEmptyList

import photography.domain.album.*
import photography.domain.layout.Spread

trait ChapterRepository[F[_]]:
  def create(album: AlbumId, chapter: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId]): F[ChapterRepository.CreateResult]

  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      countries: NonEmptyList[CountryId],
      period: VisitPeriod,
      sourceFolder: PhotoSource,
      order: NonEmptyList[ChapterId]
  ): F[ChapterRepository.UpdateResult]

object ChapterRepository:
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
