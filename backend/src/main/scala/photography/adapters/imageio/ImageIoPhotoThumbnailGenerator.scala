package photography.adapters.imageio

import photography.ports.PhotoThumbnailGenerator

trait ImageIoPhotoThumbnailGenerator[F[_]] extends PhotoThumbnailGenerator[F]
