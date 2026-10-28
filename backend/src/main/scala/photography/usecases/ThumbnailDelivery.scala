package photography.usecases

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.domain.album.{ThumbnailBytes, ThumbnailLocation}

trait ThumbnailDelivery[F[_]]:
  def get(location: ThumbnailLocation, version: String :| Not[Blank]): F[ThumbnailDelivery.GetResult]

object ThumbnailDelivery:
  enum GetResult:
    case Found(bytes: ThumbnailBytes)
    case NotFound, Failed
