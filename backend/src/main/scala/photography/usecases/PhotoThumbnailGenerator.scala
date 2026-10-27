package photography.usecases

import photography.domain.album.{EncodedThumbnail, PhotoContent, ThumbnailQuality}

trait PhotoThumbnailGenerator[F[_]]:
  def quality: ThumbnailQuality
  def generate(content: PhotoContent): F[PhotoThumbnailGenerator.GenerationResult]

object PhotoThumbnailGenerator:
  enum GenerationResult:
    case Generated(image: EncodedThumbnail)
    case Failed
