package photography.usecases.cover

import cats.MonadThrow
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverPlacementRepository
import photography.ports.cover.CoverPlacementRepository.Composition
import photography.ports.photo.PlacementCollectionRepository
import photography.usecases.cover.CoverPlacementManager.*

trait CoverPlacementManager[F[_]]:
  def read(album: AlbumId): F[ReadResult]
  def edit(album: AlbumId, request: Edit): F[Result]

object CoverPlacementManager:
  def make[F[_]: MonadCancelThrow: Mutex: IdGenerator: Logger, Db[_]: MonadThrow](
      covers: CoverPlacementRepository[Db],
      collection: PlacementCollectionRepository[Db],
      editor: CoverPlacementEditor
  )(using Transaction[Db, F]): CoverPlacementManager[F] =
    new LiveCoverPlacementManager(covers, collection, editor)

  final private class LiveCoverPlacementManager[F[_]: MonadCancelThrow: Mutex: IdGenerator: Logger, Db[_]: MonadThrow](
      covers: CoverPlacementRepository[Db],
      collection: PlacementCollectionRepository[Db],
      editor: CoverPlacementEditor
  )(using Transaction[Db, F]) extends CoverPlacementManager[F]:
    override def read(album: AlbumId): F[ReadResult] =
      val read = covers.read(album).map(_.fold[ReadResult](ReadResult.CoverNotFound)(ReadResult.Found(_)))
      coordinate(ReadResult.Failed)(Transaction[Db, F].run(read))

    override def edit(album: AlbumId, request: Edit): F[Result] =
      coordinate(Result.Failed):
        prepareEdit(request).flatMap: prepared =>
          val mutation = covers.read(album).flatMap:
            case None          => Result.CoverNotFound.pure[Db]
            case Some(current) =>
              decideEdit(current, prepared).flatMap:
                case Left(reason)      => Result.Rejected(reason).pure[Db]
                case Right(placements) =>
                  covers.replace(album, placements = placements).as(Result.Saved(current.copy(placements = placements)): Result)
          Transaction[Db, F].run(mutation)

    private def decideEdit(current: Composition, request: CoverPlacementEditor.Edit) =
      val slots = Set(current.cover.front.id, current.cover.back.id)
      request match
        case CoverPlacementEditor.Edit.Place(_, photo, _) =>
          collection.read(current.cover.album, maybeChapter = none[ChapterId]).map: inventory =>
            if !inventory.eligible.contains(photo) then Failure.PhotoNotInCollection.asLeft[Vector[CoverPlacement]]
            else if inventory.placed.contains(photo) then Failure.PhotoAlreadyUsed.asLeft[Vector[CoverPlacement]]
            else arrangeEdit(slots = slots, placements = current.placements, request)
        case _ => arrangeEdit(slots = slots, placements = current.placements, request).pure[Db]

    private def arrangeEdit(slots: Set[CoverSlotId], placements: Vector[CoverPlacement], request: CoverPlacementEditor.Edit) =
      editor.edit(slots = slots, placements = placements, request) match
        case CoverPlacementEditor.Result.Changed(placements) =>
          placements.asRight[Failure]
        case CoverPlacementEditor.Result.Rejected(reason) =>
          val failure = reason match
            case CoverPlacementEditor.Failure.SlotNotFound      => Failure.SlotNotFound
            case CoverPlacementEditor.Failure.PlacementNotFound => Failure.PlacementNotFound
            case CoverPlacementEditor.Failure.InvalidTransform  => Failure.InvalidTransform
          failure.asLeft[Vector[CoverPlacement]]

    private def prepareEdit(request: Edit) = request match
      case Edit.Place(slot, photo) =>
        IdGenerator[F].generateId.map(id => CoverPlacementEditor.Edit.Place(slot, photo, CoverPlacementId(id)))
      case Edit.Remove(id)                => CoverPlacementEditor.Edit.Remove(id).pure[F]
      case Edit.Adjust(id, transform)     => CoverPlacementEditor.Edit.Adjust(id, transform).pure[F]
      case Edit.Move(source, destination) => CoverPlacementEditor.Edit.Move(source, destination).pure[F]

    private def coordinate[A](failed: A)(body: F[A]) =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround(body).handleErrorWith(error => Logger[F].error(error)("Cover placement action failed").as(failed))

  enum Edit:
    case Place(slot: CoverSlotId, photo: PhotoId)
    case Remove(id: CoverPlacementId)
    case Adjust(id: CoverPlacementId, transform: PhotoTransform)
    case Move(source: CoverPlacementId, destination: CoverSlotId)

  enum ReadResult:
    case Found(composition: Composition)
    case CoverNotFound, Failed

  enum Result:
    case Saved(composition: Composition)
    case Rejected(reason: Failure)
    case CoverNotFound, Failed

  enum Failure derives CanEqual:
    case PhotoNotInCollection, PhotoAlreadyUsed, SlotNotFound, PlacementNotFound, InvalidTransform
