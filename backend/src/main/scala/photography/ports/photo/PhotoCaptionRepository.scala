package photography.ports.photo

import photography.domain.album.PhotoId

/** Caption-only writes are atomic and survive photo reconciliation. Uncertain outcomes require an authoritative read before retry. */
trait PhotoCaptionRepository[F[_]]:
  def read(photo: PhotoId): F[PhotoCaptionRepository.ReadResult]
  def save(photo: PhotoId, maybeCaption: Option[String]): F[PhotoCaptionRepository.WriteResult]

object PhotoCaptionRepository:
  enum ReadResult derives CanEqual:
    case Found(maybeCaption: Option[String])
    case NotFound, Failed

  enum WriteResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain
