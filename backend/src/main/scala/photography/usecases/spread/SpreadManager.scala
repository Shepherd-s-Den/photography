package photography.usecases.spread

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{AlbumId, ChapterId, InteriorSpreadCapacity}
import photography.domain.layout.*
import photography.ports.album.AlbumCompositionRepository
import photography.ports.spread.SpreadRepository
import photography.ports.album.AlbumCompositionRepository.Located
import photography.syntax.EitherSyntax.*
import photography.syntax.EitherSyntax.Pure.*
import photography.usecases.spread.SpreadManager.*
import photography.usecases.spread.SpreadTemplateManager.FindTemplateResult

trait SpreadManager[F[_]]:
  def add(album: AlbumId, chapter: ChapterId, after: SpreadId): F[AddResult]
  def move(album: AlbumId, spread: SpreadId, direction: Direction): F[MoveResult]
  def changeTemplate(album: AlbumId, spread: SpreadId, template: SpreadTemplateId, confirmPlacements: Boolean): F[ChangeTemplateResult]
  def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): F[DeleteResult]

object SpreadManager:
  def make[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      templates: SpreadTemplateManager[F],
      composition: AlbumCompositionRepository[Db],
      spreads: SpreadRepository[Db],
      defaultPhotoLayout: SpreadTemplateId
  )(using Transaction[Db, F]): SpreadManager[F] =
    new LiveSpreadManager(templates, composition, spreads, defaultPhotoLayout)

  final private class LiveSpreadManager[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      templates: SpreadTemplateManager[F],
      composition: AlbumCompositionRepository[Db],
      spreads: SpreadRepository[Db],
      defaultPhotoLayout: SpreadTemplateId
  )(using Transaction[Db, F]) extends SpreadManager[F]:
    override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): F[AddResult] =
      act[AddResult](AddResult.Failed):
        templates.findTemplate(defaultPhotoLayout).flatMap[AddResult]:
          case FindTemplateResult.Found(layout: SpreadTemplate.PhotosOnly) =>
            buildPhotoSpread(layout).flatMap: fresh =>
              val mutation =
                for
                  _        <- composition.hasAlbum(album).map(Either.cond(_, (), AddResult.AlbumNotFound: AddResult)).eitherT
                  order    <- composition.findChapterOrder(album, chapter).orFail(ifEmpty = AddResult.ChapterNotFound: AddResult)
                  target   <- composition.findSpread(album, after).orFail(ifEmpty = AddResult.SpreadNotFound: AddResult)
                  _        <- (!isAlbumOpening(target)).ensure[Db, AddResult](ifFalse = AddResult.Rejected(Conflict.AddTarget))
                  inserted <- insertAfter(order = order, after, fresh.id).orFail[Db, AddResult](ifEmpty = AddResult.SpreadNotFound)
                  count    <- composition.countInteriorSpreads(album).lift[AddResult]
                  _        <- (!InteriorSpreadCapacity.isReached(count)).ensure[Db, AddResult](ifFalse = AddResult.Rejected(Conflict.AtCapacity))
                  _        <- (spreads.insert(chapter, fresh) >> spreads.reorder(chapter, order = inserted)).lift[AddResult]
                yield AddResult.Added(fresh.id): AddResult
              Transaction[Db, F].run(mutation.merge)
          case _ => AddResult.Failed.pure[F]

    override def move(album: AlbumId, spread: SpreadId, direction: Direction): F[MoveResult] =
      val mutation =
        for
          _       <- composition.hasAlbum(album).map(Either.cond(_, (), MoveResult.AlbumNotFound: MoveResult)).eitherT
          target  <- composition.findSpread(album, spread).orFail(ifEmpty = MoveResult.SpreadNotFound: MoveResult)
          photo   <- findPhoto(target).orFail[Db, MoveResult](ifEmpty = MoveResult.Rejected(Conflict.OpeningMove))
          order   <- composition.findChapterOrder(album, photo.chapter).orFail(ifEmpty = MoveResult.SpreadNotFound: MoveResult)
          swapped <- swapNeighbour(order = order, spread, direction).orFail[Db, MoveResult](ifEmpty = MoveResult.Rejected(Conflict.NoNeighbour))
          _       <- spreads.reorder(photo.chapter, order = swapped).lift[MoveResult]
        yield MoveResult.Moved(spread): MoveResult
      act[MoveResult](MoveResult.Failed)(Transaction[Db, F].run(mutation.merge))

    override def changeTemplate(
        album: AlbumId,
        spread: SpreadId,
        template: SpreadTemplateId,
        confirmPlacements: Boolean
    ): F[ChangeTemplateResult] =
      act[ChangeTemplateResult](ChangeTemplateResult.Failed):
        templates.findTemplate(template).flatMap[ChangeTemplateResult]:
          case FindTemplateResult.Found(layout) =>
            buildReplacement(layout).flatMap: replace =>
              val mutation =
                for
                  _      <- composition.hasAlbum(album).map(Either.cond(_, (), ChangeTemplateResult.AlbumNotFound: ChangeTemplateResult)).eitherT
                  target <- composition.findSpread(album, spread).orFail(ifEmpty = ChangeTemplateResult.SpreadNotFound: ChangeTemplateResult)
                  fresh  <- replace(target.spread).orFail[Db, ChangeTemplateResult](ifEmpty = ChangeTemplateResult.Rejected(Conflict.ContextMismatch))
                  count = target.spread.placements.size
                  _ <- checkConfirmation(count = count, confirmed = confirmPlacements).leftMap(ChangeTemplateResult.Occupied(_)).eitherT[Db]
                  _ <- spreads.replace(fresh).lift[ChangeTemplateResult]
                yield ChangeTemplateResult.Changed(spread, template): ChangeTemplateResult
              Transaction[Db, F].run(mutation.merge)
          case FindTemplateResult.NotFound => (ChangeTemplateResult.Rejected(Conflict.UnknownTemplate): ChangeTemplateResult).pure[F]
          case FindTemplateResult.Failed   => (ChangeTemplateResult.Failed: ChangeTemplateResult).pure[F]

    override def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): F[DeleteResult] =
      val mutation =
        for
          _      <- composition.hasAlbum(album).map(Either.cond(_, (), DeleteResult.AlbumNotFound: DeleteResult)).eitherT
          target <- composition.findSpread(album, spread).orFail(ifEmpty = DeleteResult.SpreadNotFound: DeleteResult)
          photo  <- findPhoto(target).orFail[Db, DeleteResult](ifEmpty = DeleteResult.Rejected(Conflict.OpeningDelete))
          count = photo.value.placements.size
          _     <- checkConfirmation(count = count, confirmed = confirmPlacements).leftMap(DeleteResult.Occupied(_): DeleteResult).eitherT[Db]
          order <- composition.findChapterOrder(album, photo.chapter).orFail(ifEmpty = DeleteResult.SpreadNotFound: DeleteResult)
          remaining = removeFromOrder(order = order, spread)
          _ <- (spreads.remove(spread) >> spreads.reorder(photo.chapter, order = remaining)).lift[DeleteResult]
        yield DeleteResult.Deleted: DeleteResult
      act[DeleteResult](DeleteResult.Failed)(Transaction[Db, F].run(mutation.merge))

    private def act[A](failed: A)(body: F[A]) =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround(body).handleErrorWith(error => Logger[F].error(error)("Spread action failed").as(failed))

    private def isAlbumOpening(target: Located) = target match
      case Located.AlbumOpening(_) => true
      case _                       => false

    private def findPhoto(target: Located) = target match
      case photo: Located.Photo => photo.some
      case _                    => none[Located.Photo]

    private def insertAfter(order: NonEmptyList[SpreadId], after: SpreadId, fresh: SpreadId) =
      val index = order.toList.indexWhere(_.value === after.value)
      Option.when(index >= 0)(NonEmptyList(order.head, tail = order.tail.patch(index, List(fresh), 0)))

    private def swapNeighbour(order: NonEmptyList[SpreadId], spread: SpreadId, direction: Direction) =
      val photos    = order.tail
      val index     = photos.indexWhere(_.value === spread.value)
      val neighbour = direction match
        case Direction.Earlier => index - 1
        case Direction.Later   => index + 1
      photos.lift(neighbour).map: other =>
        val swapped = photos.zipWithIndex.map { case (id, position) =>
          if position === index then other else if position === neighbour then spread else id
        }
        NonEmptyList(order.head, tail = swapped)

    private def removeFromOrder(order: NonEmptyList[SpreadId], spread: SpreadId) =
      NonEmptyList(order.head, tail = order.tail.filterNot(_.value === spread.value))

    private def buildReplacement(layout: SpreadTemplate) = layout match
      case template: SpreadTemplate.AlbumStart   => allocateAlbumLayout(template)
      case template: SpreadTemplate.ChapterStart => allocateChapterLayout(template)
      case template: SpreadTemplate.PhotosOnly   => allocatePhotoLayout(template)

    private def allocateAlbumLayout(template: SpreadTemplate.AlbumStart) =
      (template.photoSlots.traverse(buildPhotoSlot), buildTextSlot(template.textSlot)).mapN: (photos, text) =>
        val replace: Spread => Option[Spread] =
          case opening: Spread.AlbumStart =>
            val retained = retainPlacements(previous = opening.photoSlots, placements = opening.placements, slots = photos)
            Spread.AlbumStart(opening.id, template.id, photoSlots = photos, text, placements = retained).some
          case _ => none[Spread]
        replace

    private def allocateChapterLayout(template: SpreadTemplate.ChapterStart) =
      (template.photoSlots.traverse(buildPhotoSlot), buildTextSlot(template.textSlot)).mapN: (photos, text) =>
        val replace: Spread => Option[Spread] =
          case opening: Spread.ChapterStart =>
            val retained = retainPlacements(previous = opening.photoSlots, placements = opening.placements, slots = photos)
            Spread.ChapterStart(opening.id, template.id, photoSlots = photos, text, placements = retained).some
          case _ => none[Spread]
        replace

    private def allocatePhotoLayout(template: SpreadTemplate.PhotosOnly) =
      template.photoSlots.traverse(buildPhotoSlot).map: photos =>
        val replace: Spread => Option[Spread] =
          case previous: Spread.PhotosOnly =>
            val retained =
              retainPlacements(previous = previous.photoSlots.toList.toVector, placements = previous.placements, slots = photos.toList.toVector)
            Spread.PhotosOnly(previous.id, template.id, photoSlots = photos, placements = retained).some
          case _ => none[Spread]
        replace

    private def retainPlacements(previous: Vector[SpreadSlot.Photo], placements: Vector[SpreadPlacement], slots: Vector[SpreadSlot.Photo]) =
      placements.flatMap: placement =>
        val position = previous.indexWhere(_.id.value === placement.slot.value)
        slots.lift(position).map(slot => placement.copy(slot = slot.id, transform = untouched)).toList

    private def checkConfirmation(count: Int, confirmed: Boolean) =
      Either.cond(confirmed || count === 0, (), count)

    private def buildPhotoSpread(layout: SpreadTemplate.PhotosOnly) =
      (IdGenerator[F].generateId, layout.photoSlots.traverse(buildPhotoSlot)).mapN: (id, slots) =>
        Spread.PhotosOnly(SpreadId(id), layout.id, photoSlots = slots, placements = Vector.empty[SpreadPlacement])

    private def buildPhotoSlot(box: SlotBox) =
      IdGenerator[F].generateId.map(id => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = Set.empty[SpreadEdge]))

    private def buildTextSlot(box: SlotBox) =
      IdGenerator[F].generateId.map(id => SpreadSlot.Text(TextSpreadSlotId(id), box))

    private val untouched = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)

  enum Direction derives CanEqual:
    case Earlier, Later

  enum Conflict derives CanEqual:
    case AtCapacity, AddTarget, ContextMismatch, OpeningMove, OpeningDelete, NoNeighbour, UnknownTemplate

  enum AddResult derives CanEqual:
    case Added(id: SpreadId)
    case Rejected(conflict: Conflict)
    case AlbumNotFound, ChapterNotFound, SpreadNotFound, Failed

  enum MoveResult derives CanEqual:
    case Moved(id: SpreadId)
    case Rejected(conflict: Conflict)
    case AlbumNotFound, SpreadNotFound, Failed

  enum ChangeTemplateResult derives CanEqual:
    case Changed(id: SpreadId, template: SpreadTemplateId)
    case Occupied(placements: Int)
    case Rejected(conflict: Conflict)
    case AlbumNotFound, SpreadNotFound, Failed

  enum DeleteResult derives CanEqual:
    case Deleted
    case Occupied(placements: Int)
    case Rejected(conflict: Conflict)
    case AlbumNotFound, SpreadNotFound, Failed
