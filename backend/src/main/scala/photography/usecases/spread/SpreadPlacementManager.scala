package photography.usecases.spread

import cats.MonadThrow
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.album.AlbumCompositionRepository
import photography.ports.album.AlbumCompositionRepository.Located
import photography.ports.photo.PlacementCollectionRepository
import photography.ports.spread.SpreadRepository
import photography.usecases.spread.SpreadPlacementManager.*

trait SpreadPlacementManager[F[_]]:
  def read(album: AlbumId, spread: SpreadId): F[ReadResult]
  def edit(album: AlbumId, spread: SpreadId, request: Edit): F[Result]

object SpreadPlacementManager:
  def make[F[_]: MonadCancelThrow: Mutex: IdGenerator: Logger, Db[_]: MonadThrow](
      composition: AlbumCompositionRepository[Db],
      spreads: SpreadRepository[Db],
      collection: PlacementCollectionRepository[Db],
      editor: SpreadPlacementEditor
  )(using Transaction[Db, F]): SpreadPlacementManager[F] =
    new LiveSpreadPlacementManager(composition, spreads, collection, editor)

  final private class LiveSpreadPlacementManager[F[_]: MonadCancelThrow: Mutex: IdGenerator: Logger, Db[_]: MonadThrow](
      composition: AlbumCompositionRepository[Db],
      spreads: SpreadRepository[Db],
      collection: PlacementCollectionRepository[Db],
      editor: SpreadPlacementEditor
  )(using Transaction[Db, F]) extends SpreadPlacementManager[F]:
    override def read(album: AlbumId, spread: SpreadId): F[ReadResult] =
      val read = composition.hasAlbum(album).flatMap:
        case false => ReadResult.AlbumNotFound.pure[Db]
        case true => composition.findSpread(album, spread).map(_.fold[ReadResult](ReadResult.SpreadNotFound)(value => ReadResult.Found(value.spread)))
      coordinate(ReadResult.Failed)(Transaction[Db, F].run(read))

    override def edit(album: AlbumId, spread: SpreadId, request: Edit): F[Result] =
      coordinate(Result.Failed):
        prepareEdit(request).flatMap: prepared =>
          val mutation = composition.hasAlbum(album).flatMap:
            case false => Result.AlbumNotFound.pure[Db]
            case true  =>
              composition.findSpread(album, spread).flatMap:
                case None         => Result.SpreadNotFound.pure[Db]
                case Some(target) =>
                  decideEdit(album, target, prepared).flatMap:
                    case Left(reason)   => Result.Rejected(reason).pure[Db]
                    case Right(updated) => spreads.replace(updated).as(Result.Saved(updated): Result)
          Transaction[Db, F].run(mutation)

    private def decideEdit(album: AlbumId, target: Located, request: SpreadPlacementEditor.Edit) =
      request match
        case SpreadPlacementEditor.Edit.Place(_, photo, _) =>
          collection.read(album, maybeChapter = findChapter(target)).map: inventory =>
            if !inventory.eligible.contains(photo) then Failure.PhotoNotInCollection.asLeft[Spread]
            else if inventory.placed.contains(photo) then Failure.PhotoAlreadyUsed.asLeft[Spread]
            else arrangeEdit(target.spread, request)
        case _ => arrangeEdit(target.spread, request).pure[Db]

    private def arrangeEdit(spread: Spread, request: SpreadPlacementEditor.Edit) =
      editor.edit(spread, request) match
        case SpreadPlacementEditor.Result.Changed(updated) =>
          updated.asRight[Failure]
        case SpreadPlacementEditor.Result.Rejected(reason) =>
          val failure = reason match
            case SpreadPlacementEditor.Failure.SlotNotFound      => Failure.SlotNotFound
            case SpreadPlacementEditor.Failure.PlacementNotFound => Failure.PlacementNotFound
            case SpreadPlacementEditor.Failure.InvalidTransform  => Failure.InvalidTransform
          failure.asLeft[Spread]

    private def prepareEdit(request: Edit) = request match
      case Edit.Place(slot, photo) =>
        IdGenerator[F].generateId.map(id => SpreadPlacementEditor.Edit.Place(slot, photo, SpreadPlacementId(id)))
      case Edit.Remove(id)                => SpreadPlacementEditor.Edit.Remove(id).pure[F]
      case Edit.Adjust(id, transform)     => SpreadPlacementEditor.Edit.Adjust(id, transform).pure[F]
      case Edit.Move(source, destination) => SpreadPlacementEditor.Edit.Move(source, destination).pure[F]

    private def findChapter(target: Located) = target match
      case Located.AlbumOpening(_)            => none[ChapterId]
      case Located.ChapterOpening(chapter, _) => chapter.some
      case Located.Photo(chapter, _)          => chapter.some

    private def coordinate[A](failed: A)(body: F[A]) =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround(body).handleErrorWith(error => Logger[F].error(error)("Photo placement action failed").as(failed))

  enum Edit:
    case Place(slot: PhotoSpreadSlotId, photo: PhotoId)
    case Remove(id: SpreadPlacementId)
    case Adjust(id: SpreadPlacementId, transform: PhotoTransform)
    case Move(source: SpreadPlacementId, destination: PhotoSpreadSlotId)

  enum ReadResult:
    case Found(spread: Spread)
    case AlbumNotFound, SpreadNotFound, Failed

  enum Result:
    case Saved(spread: Spread)
    case Rejected(reason: Failure)
    case AlbumNotFound, SpreadNotFound, Failed

  enum Failure derives CanEqual:
    case PhotoNotInCollection, PhotoAlreadyUsed, SlotNotFound, PlacementNotFound, InvalidTransform
