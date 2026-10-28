package photography.domain.album

import java.time.LocalDateTime
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

type PhotoFilenameConstraint       = Not[Blank] & Match["(?!\\.{1,2}$)[^/\\\\\\x00:]+"]
type PhotoFilename                 = String :| PhotoFilenameConstraint
type ThumbnailPixelCountConstraint = Interval.Closed[1, 2048]
type ThumbnailPixelCount           = Int :| ThumbnailPixelCountConstraint
type ThumbnailByteCountConstraint  = Interval.Closed[1, 300000]
type ThumbnailByteCount            = Int :| ThumbnailByteCountConstraint
type ThumbnailBytesConstraint      = MinLength[1] & MaxLength[300000]
type ThumbnailBytes                = Vector[Byte] :| ThumbnailBytesConstraint
type PhotoBytes                    = Vector[Byte] :| MinLength[1]

final case class OriginalPhotoLocation(source: PhotoSource, filename: PhotoFilename)
object OriginalPhotoLocation:
  given Eq[OriginalPhotoLocation] = Eq.by(original => (original.source: String, original.filename: String))

final case class PhotoMetadata(hash: String :| Not[Blank], maybeCapturedLocal: Option[LocalDateTime], dimensions: PhotoDimensions)
final case class PhotoContent(bytes: PhotoBytes)

opaque type ThumbnailLocation = UUID
object ThumbnailLocation:
  def apply(value: UUID): ThumbnailLocation               = value
  extension (location: ThumbnailLocation) def value: UUID = location

final case class ThumbnailDimensions(width: ThumbnailPixelCount, height: ThumbnailPixelCount)

enum ThumbnailQuality(val longestEdge: ThumbnailPixelCount, val maximumBytes: ThumbnailByteCount):
  case Low      extends ThumbnailQuality(longestEdge = 1024, maximumBytes = 75000)
  case Standard extends ThumbnailQuality(longestEdge = 1536, maximumBytes = 150000)
  case High     extends ThumbnailQuality(longestEdge = 2048, maximumBytes = 300000)

  private[album] def accepts(dimensions: ThumbnailDimensions, bytes: Int) =
    dimensions.width <= longestEdge && dimensions.height <= longestEdge && bytes <= maximumBytes

object ThumbnailQuality:
  given Eq[ThumbnailQuality] = Eq.by(_.ordinal)

final case class EncodedThumbnail(bytes: ThumbnailBytes, dimensions: ThumbnailDimensions, quality: ThumbnailQuality):
  require(quality.accepts(dimensions, bytes = bytes.size), "Thumbnail exceeds its quality preset")

final case class Thumbnail(
    location: ThumbnailLocation,
    version: String :| Not[Blank],
    dimensions: ThumbnailDimensions,
    quality: ThumbnailQuality,
    bytes: ThumbnailByteCount
):
  require(quality.accepts(dimensions, bytes = bytes), "Thumbnail exceeds its quality preset")
