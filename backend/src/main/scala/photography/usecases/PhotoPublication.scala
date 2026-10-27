package photography.usecases

import photography.domain.album.*

trait PhotoPublication[F[_]]:
  def publish(draft: PhotoPublication.Draft, image: EncodedThumbnail, epoch: BigInt): F[PhotoPublication.PublishResult]
  def retire(chapter: ChapterId, photo: PhotoId, thumbnail: ThumbnailLocation, epoch: BigInt): F[PhotoPublication.RetireResult]

object PhotoPublication:
  final case class Draft(
      id: PhotoId,
      chapter: ChapterId,
      original: OriginalPhotoLocation,
      metadata: PhotoMetadata,
      maybeCaption: Option[String],
      maybePrevious: Option[ThumbnailLocation]
  )

  enum PublishResult:
    case Published, Superseded
    case Failed(reason: Failure)

  enum RetireResult:
    case Retired, Superseded
    case Failed(reason: Failure)

  enum Failure:
    case Storage, Persistence, Cleanup
