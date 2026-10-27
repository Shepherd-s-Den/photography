package photography.usecases

import cats.effect.Async
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.numeric.GreaterEqual
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{PhotoRepository, PhotoStorage, ThumbnailStorage}
import photography.usecases.PhotoManager.*

trait PhotoManager[F[_]]:
  def refresh(chapter: ChapterId, source: PhotoSource): F[RefreshResult]

object PhotoManager:
  def make[F[_]: Async: IdGenerator: Logger, Db[_]](
      photoStorage: PhotoStorage[F],
      photos: PhotoRepository[Db],
      generator: PhotoThumbnailGenerator[F]
  )(using Transaction[Db, F]): PhotoManager[F] = new LivePhotoManager(photoStorage, photos, generator)

  final private class LivePhotoManager[F[_]: Async: IdGenerator: Logger, Db[_]](
      photoStorage: PhotoStorage[F],
      photos: PhotoRepository[Db],
      thumbnails: PhotoThumbnailGenerator[F]
  )(using Transaction[Db, F]) extends PhotoManager[F]:
    override def refresh(chapter: ChapterId, source: PhotoSource): F[RefreshResult] =
      photoStorage.discoverOriginals(source).flatMap:
        case PhotoStorage.DiscoveryResult.Failed                           => RefreshResult(unsupportedCount = 0, Vector(Issue.Discovery)).pure[F]
        case PhotoStorage.DiscoveryResult.Complete(originals, unsupported) =>
          reconcile(chapter, originals).map(RefreshResult(unsupported, _))
      .handleErrorWith(error => failed(error, Issue.Unexpected).map(RefreshResult(unsupportedCount = 0, _)))

    private def reconcile(chapter: ChapterId, originals: Vector[OriginalPhotoLocation]) =
      Transaction[Db, F].run(photos.listChapter(chapter)).flatMap:
        case PhotoRepository.ListResult.Failed           => Vector(Issue.Persistence).pure[F]
        case PhotoRepository.ListResult.Listed(existing) =>
          val removed = existing.filterNot(photo => originals.contains(photo.original))
          val updates = originals.traverse: original =>
            val maybePrevious = existing.find(_.original === original)
            refreshPhoto(chapter, original, maybePrevious)
          (updates, removed.traverse(retire)).mapN((updated, retired) => updated.flatten ++ retired.flatten)
      .handleErrorWith(error => failed(error, Issue.Persistence))

    private def refreshPhoto(chapter: ChapterId, original: OriginalPhotoLocation, maybePrevious: Option[Photo]) =
      val refresh = photoStorage.readOriginal(original).flatMap:
        case PhotoStorage.ReadResult.Read(content, metadata) =>
          val unchanged = maybePrevious.exists: photo =>
            (photo.metadata.hash: String) === (metadata.hash: String) && photo.thumbnail.quality === thumbnails.quality
          if unchanged then Vector.empty[Issue].pure[F]
          else generateThumbnail(chapter, original, content, metadata, maybePrevious)
        case PhotoStorage.ReadResult.Undecodable => Vector(Issue.Undecodable(original.filename)).pure[F]
        case PhotoStorage.ReadResult.Unstable    => Vector(Issue.Unstable(original.filename)).pure[F]
        case PhotoStorage.ReadResult.Failed      => Vector(Issue.Read(original.filename)).pure[F]
      refresh.handleErrorWith(error => failed(error, Issue.Unexpected))

    private def generateThumbnail(
        chapter: ChapterId,
        original: OriginalPhotoLocation,
        content: PhotoContent,
        metadata: PhotoMetadata,
        maybePrevious: Option[Photo]
    ) =
      thumbnails.generate(content).flatMap:
        case PhotoThumbnailGenerator.GenerationResult.Failed           => Vector(Issue.Generation(original.filename)).pure[F]
        case PhotoThumbnailGenerator.GenerationResult.Generated(image) =>
          val maybeCaption = maybePrevious.flatMap(_.maybeCaption)
          val identity     = maybePrevious.fold(IdGenerator[F].generateId.map(PhotoId(_)))(_.id.pure[F])
          identity.flatMap: id =>
            IdGenerator[F].generateId.map(ThumbnailLocation(_)).flatMap: location =>
              publish(location, Photo(id, chapter, original, metadata, _, maybeCaption), image, maybePrevious)

    private def publish(location: ThumbnailLocation, photo: Thumbnail => Photo, image: EncodedThumbnail, maybePrevious: Option[Photo]) =
      Async[F].uncancelable: _ =>
        photoStorage.storeThumbnail(location, image).attempt.flatMap:
          case Right(ThumbnailStorage.StoreResult.Failed) => cleanup(location).map(Issue.Storage +: _)
          case Left(error) => Logger[F].error(error)("Photo thumbnail storage failed") >> cleanup(location).map(Issue.Storage +: _)
          case Right(ThumbnailStorage.StoreResult.Stored(thumbnail)) =>
            Transaction[Db, F].run(photos.save(photo(thumbnail))).attempt.flatMap:
              case Right(PhotoRepository.WriteResult.Applied) =>
                maybePrevious.traverse(old => cleanup(old.thumbnail.location)).map(_.getOrElse(Vector.empty[Issue]))
              case Right(PhotoRepository.WriteResult.Failed) => cleanup(location).map(Issue.Persistence +: _)
              case Left(error) => Logger[F].error(error)("Photo persistence failed") >> cleanup(location).map(Issue.Persistence +: _)

    private def retire(photo: Photo) =
      Async[F].uncancelable: _ =>
        Transaction[Db, F].run(photos.remove(photo.id)).flatMap:
          case PhotoRepository.WriteResult.Applied => cleanup(photo.thumbnail.location)
          case PhotoRepository.WriteResult.Failed  => Vector(Issue.Persistence).pure[F]
      .handleErrorWith(error => failed(error, Issue.Persistence))

    private def cleanup(location: ThumbnailLocation) =
      photoStorage.removeThumbnail(location).attempt.flatMap:
        case Right(ThumbnailStorage.RemoveResult.Removed) => Vector.empty[Issue].pure[F]
        case Right(ThumbnailStorage.RemoveResult.Failed)  => Vector(Issue.Cleanup(location)).pure[F]
        case Left(error)                                  => failed(error, Issue.Cleanup(location))

    private def failed(error: Throwable, issue: Issue) =
      Logger[F].error(error)(s"Photo reconciliation failed: $issue").as(Vector(issue))

  final case class RefreshResult(unsupportedCount: Int :| GreaterEqual[0], issues: Vector[Issue])

  enum Issue:
    case Discovery, Persistence, Storage, Unexpected
    case Read(filename: PhotoFilename)
    case Generation(filename: PhotoFilename)
    case Undecodable(filename: PhotoFilename)
    case Unstable(filename: PhotoFilename)
    case Cleanup(location: ThumbnailLocation)
