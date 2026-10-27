package photography.ports

import photography.domain.album.*

trait ThumbnailStorage[F[_]]:
  def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): F[ThumbnailStorage.StoreResult]
  def removeThumbnail(location: ThumbnailLocation): F[ThumbnailStorage.RemoveResult]

object ThumbnailStorage:
  enum StoreResult:
    case Stored(thumbnail: Thumbnail)
    case Failed

  enum RemoveResult:
    case Removed, Failed
