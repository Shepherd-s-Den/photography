package photography.domain.album

import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import cats.Eq

opaque type PhotoId = UUID
object PhotoId:
  def apply(value: UUID): PhotoId         = value
  extension (id: PhotoId) def value: UUID = id

type PixelCount = Int :| Positive

final case class PhotoDimensions(width: PixelCount, height: PixelCount)

final case class Photo(
    id: PhotoId,
    chapter: ChapterId,
    original: OriginalPhotoLocation,
    metadata: PhotoMetadata,
    thumbnail: Thumbnail,
    maybeCaption: Option[String]
)

object Photo:
  given Ordering[Photo] = Ordering.by: photo =>
    val filename = photo.original.filename
    (
      photo.metadata.maybeCapturedLocal.isEmpty,
      photo.metadata.maybeCapturedLocal.getOrElse(LocalDateTime.MIN),
      filename.toLowerCase(Locale.ROOT),
      filename,
      photo.chapter.value.toString
    )

final case class OriginalPhotoLocation(source: PhotoSource, relative: RelativeLocation):
  def filename: String = (relative: String).substring((relative: String).lastIndexOf('/') + 1)

object OriginalPhotoLocation:
  given Eq[OriginalPhotoLocation] = Eq.by(original => (original.source: String, original.relative: String))

final case class PhotoMetadata(hash: String :| Not[Blank], maybeCapturedLocal: Option[LocalDateTime], dimensions: PhotoDimensions)
final case class PhotoContent(bytes: Vector[Byte])

opaque type ThumbnailLocation = UUID
object ThumbnailLocation:
  def apply(value: UUID): ThumbnailLocation               = value
  extension (location: ThumbnailLocation) def value: UUID = location

enum ThumbnailQuality(val longestEdge: PixelCount, val maximumBytes: Int):
  case Low      extends ThumbnailQuality(longestEdge = 1024, maximumBytes = 75000)
  case Standard extends ThumbnailQuality(longestEdge = 1536, maximumBytes = 150000)
  case High     extends ThumbnailQuality(longestEdge = 2048, maximumBytes = 300000)

  def accepts(dimensions: PhotoDimensions, bytes: Int): Boolean =
    bytes > 0 && bytes <= maximumBytes && dimensions.width <= longestEdge && dimensions.height <= longestEdge

object ThumbnailQuality:
  given Eq[ThumbnailQuality] = Eq.by(_.ordinal)

final class EncodedThumbnail private (val content: PhotoContent, val dimensions: PhotoDimensions, val quality: ThumbnailQuality)
object EncodedThumbnail:
  def from(content: PhotoContent, dimensions: PhotoDimensions, quality: ThumbnailQuality): Option[EncodedThumbnail] =
    Option.when(quality.accepts(dimensions, content.bytes.size))(new EncodedThumbnail(content, dimensions, quality))

final class Thumbnail private (
    val location: ThumbnailLocation,
    val version: String :| Not[Blank],
    val dimensions: PhotoDimensions,
    val quality: ThumbnailQuality,
    val bytes: Int
)

object Thumbnail:
  def from(
      location: ThumbnailLocation,
      version: String :| Not[Blank],
      dimensions: PhotoDimensions,
      quality: ThumbnailQuality,
      bytes: Int
  ): Option[Thumbnail] =
    Option.when(quality.accepts(dimensions, bytes))(new Thumbnail(location, version, dimensions, quality, bytes))
