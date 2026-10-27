package photography.usecases

import photography.domain.album.{EncodedThumbnail, PhotoContent}

trait PhotoThumbnailGenerator[F[_]]:
  def generate(content: PhotoContent): F[PhotoThumbnailGenerator.GenerationResult]

object PhotoThumbnailGenerator:
  enum GenerationResult:
    case Generated(image: EncodedThumbnail)
    case Failed
