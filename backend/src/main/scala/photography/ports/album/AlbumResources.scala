package photography.ports.album

import photography.domain.album.*
import photography.domain.layout.SpreadId
import java.time.{Instant, LocalDate}

trait AlbumResources[F[_]]:
  def metadata(album: AlbumId): F[Either[AlbumResources.Failure, Option[AlbumResources.Metadata]]]
  def chapters(album: AlbumId): F[Either[AlbumResources.Failure, Option[Vector[AlbumResources.ChapterMetadata]]]]
  def composition(album: AlbumId): F[Either[AlbumResources.Failure, Option[Vector[AlbumResources.Member]]]]

object AlbumResources:
  enum Failure:
    case Unavailable
  final case class Member(id: SpreadId, chapter: Option[ChapterId])

  final case class Metadata(id: AlbumId, year: AlbumYear, title: AlbumTitle, synopsis: String, createdAt: Instant)
  final case class ChapterMetadata(
      id: ChapterId,
      name: DestinationName,
      countries: Vector[CountryId],
      start: LocalDate,
      end: LocalDate,
      sourceFolder: PhotoSource,
      description: String
  )
