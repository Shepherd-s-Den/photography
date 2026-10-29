package photography.usecases

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.kernel.MonadCancelThrow
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import org.typelevel.log4cats.Logger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{Album, AlbumId, Chapter, ChapterId}
import photography.domain.layout.*
import photography.ports.SpreadRepository
import photography.usecases.SpreadManager.*
import photography.usecases.SpreadTemplateManager.FindTemplateResult

trait SpreadManager[F[_]]:
  def add(album: AlbumId, chapter: ChapterId, after: SpreadId): F[AddResult]
  def move(album: AlbumId, spread: SpreadId, direction: Direction): F[MoveResult]
  def changeTemplate(album: AlbumId, spread: SpreadId, template: SpreadTemplateId, confirmPlacements: Boolean): F[ChangeTemplateResult]
  def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): F[DeleteResult]

object SpreadManager:
  def make[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      templates: SpreadTemplateManager[F],
      spreads: SpreadRepository[Db],
      defaultPhotoLayout: SpreadTemplateId
  )(using Transaction[Db, F]): SpreadManager[F] =
    new LiveSpreadManager(templates, spreads, defaultPhotoLayout)

  final private class LiveSpreadManager[F[_]: MonadCancelThrow: IdGenerator: Mutex: Logger, Db[_]: MonadThrow](
      templates: SpreadTemplateManager[F],
      spreads: SpreadRepository[Db],
      defaultPhotoLayout: SpreadTemplateId
  )(using Transaction[Db, F]) extends SpreadManager[F]:
    private val untouched = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)

    override def add(album: AlbumId, chapter: ChapterId, after: SpreadId): F[AddResult] =
      act[AddResult](AddResult.Failed):
        templates.findTemplate(defaultPhotoLayout).flatMap[AddResult]:
          case FindTemplateResult.Found(layout: SpreadTemplate.PhotosOnly) =>
            freshPhotoSpread(layout).flatMap(fresh => Transaction[Db, F].run(addProgram(album, chapter, after, fresh)))
          case _ => AddResult.Failed.pure[F]

    override def move(album: AlbumId, spread: SpreadId, direction: Direction): F[MoveResult] =
      act[MoveResult](MoveResult.Failed)(Transaction[Db, F].run(moveProgram(album, spread, direction)))

    override def changeTemplate(
        album: AlbumId,
        spread: SpreadId,
        template: SpreadTemplateId,
        confirmPlacements: Boolean
    ): F[ChangeTemplateResult] =
      act[ChangeTemplateResult](ChangeTemplateResult.Failed):
        templates.findTemplate(template).flatMap[ChangeTemplateResult]:
          case FindTemplateResult.Found(layout) =>
            freshLayout(layout).flatMap(fresh => Transaction[Db, F].run(changeProgram(album, spread, layout, fresh, confirmPlacements)))
          case FindTemplateResult.NotFound => (ChangeTemplateResult.Rejected(Conflict.UnknownTemplate): ChangeTemplateResult).pure[F]
          case FindTemplateResult.Failed   => (ChangeTemplateResult.Failed: ChangeTemplateResult).pure[F]

    override def delete(album: AlbumId, spread: SpreadId, confirmPlacements: Boolean): F[DeleteResult] =
      act[DeleteResult](DeleteResult.Failed)(Transaction[Db, F].run(deleteProgram(album, spread, confirmPlacements)))

    private def act[A](failed: A)(body: F[A]): F[A] =
      MonadCancelThrow[F].uncancelable: _ =>
        summon[Mutex[F]].lock.surround(body).handleErrorWith(error => Logger[F].error(error)("Spread action failed").as(failed))

    private def addProgram(album: AlbumId, chapter: ChapterId, after: SpreadId, fresh: Spread.PhotosOnly): Db[AddResult] =
      spreads.loadAlbum(album).flatMap[AddResult]:
        case None          => AddResult.AlbumNotFound.pure[Db]
        case Some(current) =>
          current.chapters.find(_.id.value === chapter.value) match
            case None                                                => AddResult.ChapterNotFound.pure[Db]
            case Some(_) if current.opening.id.value === after.value => AddResult.Rejected(Conflict.AddTarget).pure[Db]
            case Some(target)                                        =>
              insertionIndex(target, after) match
                case None                                                               => AddResult.SpreadNotFound.pure[Db]
                case Some(_) if current.collectSpreads.length >= InteriorSpreadCapacity => AddResult.Rejected(Conflict.AtCapacity).pure[Db]
                case Some(index)                                                        =>
                  val photos = target.spreads.patch(index, Vector(fresh), 0)
                  spreads.insert(target.id, fresh) >> spreads.reorder(target.id, orderOf(target, photos)).as(AddResult.Added(fresh.id))

    private def moveProgram(album: AlbumId, spread: SpreadId, direction: Direction): Db[MoveResult] =
      spreads.loadAlbum(album).flatMap[MoveResult]:
        case None          => MoveResult.AlbumNotFound.pure[Db]
        case Some(current) =>
          locate(current, spread) match
            case None                                                   => MoveResult.SpreadNotFound.pure[Db]
            case Some(Located(photo: Spread.PhotosOnly, Some(chapter))) => moveWithinChapter(chapter, photo, direction)
            case Some(_)                                                => MoveResult.Rejected(Conflict.OpeningMove).pure[Db]

    private def moveWithinChapter(chapter: Chapter, photo: Spread.PhotosOnly, direction: Direction): Db[MoveResult] =
      val index     = chapter.spreads.indexWhere(_.id.value === photo.id.value)
      val neighbour = direction match
        case Direction.Earlier => index - 1
        case Direction.Later   => index + 1
      chapter.spreads.lift(neighbour) match
        case None        => MoveResult.Rejected(Conflict.NoNeighbour).pure[Db]
        case Some(other) =>
          val swapped = chapter.spreads.zipWithIndex.map { case (existing, position) =>
            if position === index then other else if position === neighbour then photo else existing
          }
          spreads.reorder(chapter.id, orderOf(chapter, swapped)).as(MoveResult.Moved(photo.id))

    private def changeProgram(
        album: AlbumId,
        spread: SpreadId,
        layout: SpreadTemplate,
        fresh: FreshSlots,
        confirm: Boolean
    ): Db[ChangeTemplateResult] =
      spreads.loadAlbum(album).flatMap[ChangeTemplateResult]:
        case None          => ChangeTemplateResult.AlbumNotFound.pure[Db]
        case Some(current) =>
          locate(current, spread) match
            case None          => ChangeTemplateResult.SpreadNotFound.pure[Db]
            case Some(located) =>
              retemplate(located.spread, layout, fresh) match
                case None                                                      => ChangeTemplateResult.Rejected(Conflict.ContextMismatch).pure[Db]
                case Some(_) if located.spread.placements.nonEmpty && !confirm =>
                  ChangeTemplateResult.Occupied(located.spread.placements.size).pure[Db]
                case Some(replacement) =>
                  spreads.replace(replacement).as(ChangeTemplateResult.Changed(spread, layout.id))

    private def deleteProgram(album: AlbumId, spread: SpreadId, confirm: Boolean): Db[DeleteResult] =
      spreads.loadAlbum(album).flatMap[DeleteResult]:
        case None          => DeleteResult.AlbumNotFound.pure[Db]
        case Some(current) =>
          locate(current, spread) match
            case None                                                   => DeleteResult.SpreadNotFound.pure[Db]
            case Some(Located(photo: Spread.PhotosOnly, Some(chapter))) =>
              if photo.placements.nonEmpty && !confirm then DeleteResult.Occupied(photo.placements.size).pure[Db]
              else
                val remaining = chapter.spreads.filterNot(_.id.value === spread.value)
                spreads.remove(spread) >> spreads.reorder(chapter.id, orderOf(chapter, remaining)).as(DeleteResult.Deleted)
            case Some(_) => DeleteResult.Rejected(Conflict.OpeningDelete).pure[Db]

    private def locate(current: Album, id: SpreadId): Option[Located] =
      val owned = current.chapters.flatMap: chapter =>
        (Vector[Spread](chapter.opening) ++ chapter.spreads).find(_.id.value === id.value).map(Located(_, chapter.some)).toList
      owned.headOption.orElse(Option.when(current.opening.id.value === id.value)(Located(current.opening, none[Chapter])))

    private def insertionIndex(chapter: Chapter, after: SpreadId): Option[Int] =
      if chapter.opening.id.value === after.value then 0.some
      else
        val index = chapter.spreads.indexWhere(_.id.value === after.value)
        Option.when(index >= 0)(index + 1)

    private def orderOf(chapter: Chapter, photos: Vector[Spread.PhotosOnly]): NonEmptyList[SpreadId] =
      NonEmptyList(chapter.opening.id, photos.map(_.id).toList)

    private def retemplate(spread: Spread, layout: SpreadTemplate, fresh: FreshSlots): Option[Spread] = (spread, layout) match
      case (opening: Spread.AlbumStart, template: SpreadTemplate.AlbumStart) =>
        fresh.text.map(text =>
          Spread.AlbumStart(opening.id, template.id, fresh.photos, text, retained(opening.photoSlots, opening.placements, fresh.photos))
        )
      case (opening: Spread.ChapterStart, template: SpreadTemplate.ChapterStart) =>
        fresh.text.map(text =>
          Spread.ChapterStart(opening.id, template.id, fresh.photos, text, retained(opening.photoSlots, opening.placements, fresh.photos))
        )
      case (photos: Spread.PhotosOnly, template: SpreadTemplate.PhotosOnly) =>
        NonEmptyList.fromList(fresh.photos.toList).map: slots =>
          Spread.PhotosOnly(photos.id, template.id, slots, retained(photos.photoSlots.toList.toVector, photos.placements, fresh.photos))
      case _ => none[Spread]

    private def retained(previous: Vector[SpreadSlot.Photo], placements: Vector[SpreadPlacement], slots: Vector[SpreadSlot.Photo]) =
      placements.flatMap: placement =>
        val position = previous.indexWhere(_.id.value === placement.slot.value)
        slots.lift(position).map(slot => placement.copy(slot = slot.id, transform = untouched)).toList

    private def freshPhotoSpread(layout: SpreadTemplate.PhotosOnly): F[Spread.PhotosOnly] =
      (IdGenerator[F].generateId, layout.photoSlots.traverse(photoSlot)).mapN { case (id, slots) =>
        Spread.PhotosOnly(SpreadId(id), layout.id, slots, Vector.empty[SpreadPlacement])
      }

    private def freshLayout(layout: SpreadTemplate): F[FreshSlots] =
      (photoBoxes(layout).traverse(photoSlot), textBox(layout).traverse(textSlot)).mapN(FreshSlots(_, _))

    private def photoSlot(box: SlotBox): F[SpreadSlot.Photo] =
      IdGenerator[F].generateId.map(id => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = Set.empty[SpreadEdge]))

    private def textSlot(box: SlotBox): F[SpreadSlot.Text] =
      IdGenerator[F].generateId.map(id => SpreadSlot.Text(TextSpreadSlotId(id), box))

    private def photoBoxes(layout: SpreadTemplate): Vector[SlotBox] = layout match
      case value: SpreadTemplate.AlbumStart   => value.photoSlots
      case value: SpreadTemplate.ChapterStart => value.photoSlots
      case value: SpreadTemplate.PhotosOnly   => value.photoSlots.toList.toVector

    private def textBox(layout: SpreadTemplate): Option[SlotBox] = layout match
      case value: SpreadTemplate.AlbumStart   => value.textSlot.some
      case value: SpreadTemplate.ChapterStart => value.textSlot.some
      case _: SpreadTemplate.PhotosOnly       => none[SlotBox]

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

  private val InteriorSpreadCapacity = 65

  final private case class Located(spread: Spread, chapter: Option[Chapter])

  final private case class FreshSlots(photos: Vector[SpreadSlot.Photo], text: Option[SpreadSlot.Text])
