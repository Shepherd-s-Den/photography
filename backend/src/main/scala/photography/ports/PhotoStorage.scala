package photography.ports

import photography.domain.album.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual

trait PhotoStorage[F[_]]:
  def discoverOriginals(source: PhotoSource): F[PhotoStorage.DiscoveryResult]
  def readOriginal(location: OriginalPhotoLocation): F[PhotoStorage.ReadResult]
  // The supplied exclusive location identifies even partial artifacts after a failed store.
  def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): F[PhotoStorage.StoreResult]
  def removeThumbnail(location: ThumbnailLocation): F[PhotoStorage.RemoveResult]

object PhotoStorage:
  enum DiscoveryResult:
    case Complete(originals: Vector[OriginalPhotoLocation], unsupportedCount: Int :| GreaterEqual[0])
    case Failed

  enum ReadResult:
    case Read(content: PhotoContent, metadata: PhotoMetadata)
    case Undecodable, Unstable, Failed

  enum StoreResult:
    case Stored(thumbnail: Thumbnail)
    case Failed

  enum RemoveResult:
    case Removed, Failed
