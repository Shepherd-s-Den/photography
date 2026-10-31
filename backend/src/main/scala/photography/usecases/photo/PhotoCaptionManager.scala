package photography.usecases.photo

import cats.Functor
import cats.syntax.all.*
import photography.domain.album.PhotoId
import photography.ports.photo.PhotoCaptionRepository
import photography.usecases.photo.PhotoCaptionManager.*

trait PhotoCaptionManager[F[_]]:
  def read(photo: PhotoId): F[ReadResult]
  def save(photo: PhotoId, text: String): F[SaveResult]

object PhotoCaptionManager:
  def make[F[_]: Functor](repository: PhotoCaptionRepository[F]): PhotoCaptionManager[F] = new LivePhotoCaptionManager(repository)

  final private class LivePhotoCaptionManager[F[_]: Functor](repository: PhotoCaptionRepository[F]) extends PhotoCaptionManager[F]:
    override def read(photo: PhotoId): F[ReadResult] =
      repository.read(photo).map:
        case PhotoCaptionRepository.ReadResult.Found(value) => ReadResult.Found(maybeCaption = value)
        case PhotoCaptionRepository.ReadResult.NotFound     => ReadResult.NotFound
        case PhotoCaptionRepository.ReadResult.Failed       => ReadResult.Failed

    override def save(photo: PhotoId, text: String): F[SaveResult] =
      repository.save(photo, maybeCaption = Option.when(text.nonEmpty)(text)).map:
        case PhotoCaptionRepository.WriteResult.Saved     => SaveResult.Saved
        case PhotoCaptionRepository.WriteResult.NotFound  => SaveResult.NotFound
        case PhotoCaptionRepository.WriteResult.Failed    => SaveResult.Failed
        case PhotoCaptionRepository.WriteResult.Uncertain => SaveResult.Uncertain

  enum ReadResult derives CanEqual:
    case Found(maybeCaption: Option[String])
    case NotFound, Failed

  enum SaveResult derives CanEqual:
    case Saved, NotFound, Failed, Uncertain
