package photography.ports

import photography.domain.album.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.GreaterEqual

trait PhotoFileSystem[F[_]]:
  def discoverOriginals(source: PhotoSource): F[PhotoFileSystem.DiscoveryResult]
  def readOriginal(location: OriginalPhotoLocation): F[PhotoFileSystem.ReadResult]

object PhotoFileSystem:
  enum DiscoveryResult:
    case Complete(originals: Vector[OriginalPhotoLocation], unsupportedCount: Int :| GreaterEqual[0])
    case Failed

  enum ReadResult:
    case Read(content: PhotoContent, metadata: PhotoMetadata)
    case Undecodable, Unstable, Failed
