package photography.ports

import photography.domain.album.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual

trait PhotoStorage[F[_]] extends ThumbnailStorage[F]:
  def discoverOriginals(source: PhotoSource): F[PhotoStorage.DiscoveryResult]
  def readOriginal(location: OriginalPhotoLocation): F[PhotoStorage.ReadResult]

object PhotoStorage:
  enum DiscoveryResult:
    case Complete(originals: Vector[OriginalPhotoLocation], unsupportedCount: Int :| GreaterEqual[0])
    case Failed

  enum ReadResult:
    case Read(content: PhotoContent, metadata: PhotoMetadata)
    case Undecodable, Unstable, Failed
