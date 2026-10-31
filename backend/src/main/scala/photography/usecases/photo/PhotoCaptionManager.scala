package photography.usecases.photo

import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.Transaction
import photography.domain.album.PhotoId
import photography.ports.photo.PhotoRepository
import photography.usecases.photo.PhotoCaptionManager.*

trait PhotoCaptionManager[F[_]]:
  def save(photo: PhotoId, text: String): F[SaveResult]

object PhotoCaptionManager:
  def make[F[_]: MonadCancelThrow: Mutex: Logger, Db[_]](repository: PhotoRepository[Db])(using Transaction[Db, F]): PhotoCaptionManager[F] =
    new LivePhotoCaptionManager(repository)

  final private class LivePhotoCaptionManager[F[_]: MonadCancelThrow: Mutex: Logger, Db[_]](repository: PhotoRepository[Db])(
      using Transaction[Db, F]
  ) extends PhotoCaptionManager[F]:
    override def save(photo: PhotoId, text: String): F[SaveResult] =
      summon[Mutex[F]].lock.surround:
        MonadCancelThrow[F].uncancelable: _ =>
          Transaction[Db, F].run(repository.saveCaption(photo, maybeCaption = Option.when(text.nonEmpty)(text))).flatMap:
            case PhotoRepository.SaveCaptionResult.Saved         => SaveResult.Saved.pure[F]
            case PhotoRepository.SaveCaptionResult.PhotoNotFound => SaveResult.PhotoNotFound.pure[F]
            case PhotoRepository.SaveCaptionResult.Failed(cause) => Logger[F].error(cause)("Photo caption write failed").as(SaveResult.Failed)
            case PhotoRepository.SaveCaptionResult.Uncertain     => SaveResult.Uncertain.pure[F]
          .handleErrorWith(error => Logger[F].error(error)("Photo caption commit acknowledgement failed").as(SaveResult.Uncertain))

  enum SaveResult:
    case Saved, PhotoNotFound, Failed, Uncertain
