package photography.usecases

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.SpreadRepository
import photography.usecases.SpreadManager.*
import photography.usecases.SpreadTemplateManager.FindTemplateResult

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class SpreadManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val box                             = SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0)
  private val wideBox                         = SlotBox(x = 50.0, y = 0.0, width = 50.0, height = 100.0)
  private val defaultLayout                   = SpreadTemplateId(buildUuid(index = 113))
  private val singleLayout                    = SpreadTemplateId(buildUuid(index = 114))
  private val chapterLayout                   = SpreadTemplateId(buildUuid(index = 5))
  private val altLayout                       = SpreadTemplateId(buildUuid(index = 6))
  private val albumLayout                     = SpreadTemplateId(buildUuid(index = 1))
  private val altAlbumLayout                  = SpreadTemplateId(buildUuid(index = 7))
  private val catalog: Vector[SpreadTemplate] = Vector(
    SpreadTemplate.PhotosOnly(defaultLayout, name = "4 x (L/L)", photoSlots = NonEmptyList.of(box, wideBox)),
    SpreadTemplate.PhotosOnly(singleLayout, name = "L", photoSlots = NonEmptyList.one(box)),
    SpreadTemplate.ChapterStart(chapterLayout, name = "Text + 2 x P + (L/L)", photoSlots = Vector(box, wideBox), textSlot = box),
    SpreadTemplate.ChapterStart(altLayout, name = "Text", photoSlots = Vector(box), textSlot = wideBox),
    SpreadTemplate.AlbumStart(albumLayout, name = "2 x (L/L) + Text", photoSlots = Vector(box), textSlot = wideBox),
    SpreadTemplate.AlbumStart(altAlbumLayout, name = "Synopsis", photoSlots = Vector(box, wideBox), textSlot = box)
  )
  private val untouched = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val newId     = SpreadId(buildUuid(index = 1000L))
  private val country   = Country(CountryId(buildUuid(index = 22L)), code = "JP", name = "Japan", AbsoluteLocation(Path.of("/flags/jp.svg")))

  private val albumOpening = Spread.AlbumStart(
    SpreadId(buildUuid(index = 2L)),
    albumLayout,
    photoSlots = Vector.empty[SpreadSlot.Photo],
    textSlot = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 3L)), wideBox),
    placements = Vector.empty[SpreadPlacement]
  )
  private val chapterOpening = Spread.ChapterStart(
    SpreadId(buildUuid(index = 30L)),
    chapterLayout,
    photoSlots = Vector(photoSlot(index = 32L, box)),
    textSlot = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 31L)), box),
    placements = Vector.empty[SpreadPlacement]
  )
  private val first         = photoSpread(index = 40L, placements = Vector.empty[SpreadPlacement])
  private val second        = photoSpread(index = 41L, placements = Vector.empty[SpreadPlacement])
  private val third         = photoSpread(index = 42L, placements = Vector.empty[SpreadPlacement])
  private val firstOccupied = photoSpread(
    index = 40L,
    placements = Vector(
      placement(index = 70L, slot = PhotoSpreadSlotId(buildUuid(index = 4000L))),
      placement(index = 71L, slot = PhotoSpreadSlotId(buildUuid(index = 4001L)))
    )
  )
  private val chapter = Chapter(
    ChapterId(buildUuid(index = 20L)),
    Destination(DestinationId(buildUuid(index = 21L)), name = "Asian capitals", countries = NonEmptyList.one(country)),
    VisitPeriod(firstDay = LocalDate.of(2026, 5, 1), days = 2),
    description = "",
    sourceFolder = "/photos/capitals",
    chapterOpening,
    spreads = Vector(first, second)
  )
  private val foreign      = photoSpread(index = 90L, placements = Vector.empty[SpreadPlacement])
  private val otherChapter = chapter.copy(
    id = ChapterId(buildUuid(index = 50L)),
    opening = chapterOpening.copy(id = SpreadId(buildUuid(index = 60L))),
    spreads = Vector(foreign)
  )
  private val album = Album(
    AlbumId(buildUuid(index = 1L)),
    year = 2026,
    title = "A year abroad",
    synopsis = "",
    Instant.EPOCH,
    albumOpening,
    chapters = Vector(chapter, otherChapter)
  )

  test("should insert the default photo layout after a chapter opening and select it"):
    val refs = Refs()

    for
      manager <- buildManager(refs)
      result  <- manager.add(album.id, chapter.id, after = chapterOpening.id)
      inserts <- refs.inserts.get
      orders  <- refs.orders.get
    yield
      val expectedOrder = NonEmptyList.of(chapterOpening.id, newId, first.id, second.id)
      assertEquals(result, AddResult.Added(newId))
      assertEquals(inserted(inserts), Vector((chapter.id, newId, defaultLayout, List(box, wideBox), Vector.empty[SpreadPlacement])))
      assertEquals(orders, Vector((chapter.id, expectedOrder)))

  test("should insert the default photo layout after a photo spread and keep later spreads in order"):
    val refs = Refs()

    for
      manager <- buildManager(refs)
      result  <- manager.add(album.id, chapter.id, after = first.id)
      orders  <- refs.orders.get
    yield
      assertEquals(result, AddResult.Added(newId))
      assertEquals(orders, Vector((chapter.id, NonEmptyList.of(chapterOpening.id, first.id, newId, second.id))))

  test("should reject adding after the album opening without changing the album"):
    val refs = Refs()

    for
      manager <- buildManager(refs)
      result  <- manager.add(album.id, chapter.id, after = albumOpening.id)
      inserts <- refs.inserts.get
      orders  <- refs.orders.get
    yield
      assertEquals(result, AddResult.Rejected(Conflict.AddTarget))
      assertEquals(inserts, Vector.empty[(ChapterId, Spread.PhotosOnly)])
      assertEquals(orders, Vector.empty[(ChapterId, NonEmptyList[SpreadId])])

  test("should reject adding at the 65 interior spread capacity and allow it one spread below"):
    Vector(
      (63, AddResult.Rejected(Conflict.AtCapacity), Vector.empty[ChapterId]),
      (62, AddResult.Added(newId), Vector(chapter.id))
    ).traverse_ { case (photos, expected, insertedChapters) =>
      val refs    = Refs()
      val current = album.copy(chapters =
        Vector(chapter.copy(spreads =
          Vector.tabulate(photos)(index => photoSpread(index = 200L + index.toLong, placements = Vector.empty[SpreadPlacement]))
        ))
      )

      for
        manager <- buildManager(refs, current = current.some)
        result  <- manager.add(current.id, chapter.id, after = chapterOpening.id)
        inserts <- refs.inserts.get
      yield
        assertEquals(result, expected)
        assertEquals(inserts.map(_._1), insertedChapters)
    }

  test("should report unknown album, chapter, or foreign and unknown spread targets when adding"):
    val refs = Refs()

    for
      missingAlbum   <- buildManager(refs, current = Option.empty[Album]).flatMap(_.add(album.id, chapter.id, after = chapterOpening.id))
      missingChapter <- buildManager(refs).flatMap(_.add(album.id, ChapterId(buildUuid(index = 999L)), after = chapterOpening.id))
      unknownSpread  <- buildManager(refs).flatMap(_.add(album.id, chapter.id, after = SpreadId(buildUuid(index = 999L))))
      foreignSpread  <- buildManager(refs).flatMap(_.add(album.id, chapter.id, after = foreign.id))
    yield
      assertEquals(missingAlbum, AddResult.AlbumNotFound)
      assertEquals(missingChapter, AddResult.ChapterNotFound)
      assertEquals(unknownSpread, AddResult.SpreadNotFound)
      assertEquals(foreignSpread, AddResult.SpreadNotFound)

  test("should fail adding when the default layout is unavailable or has the wrong context"):
    val refs      = Refs()
    val missing   = FindTemplateResult.NotFound
    val wrongKind = FindTemplateResult.Found(SpreadTemplate.ChapterStart(chapterLayout, name = "Text", photoSlots = Vector(box), textSlot = box))

    for
      unavailable <- buildManager(refs, outcome = missing.some).flatMap(_.add(album.id, chapter.id, after = chapterOpening.id))
      wrong       <- buildManager(refs, outcome = wrongKind.some).flatMap(_.add(album.id, chapter.id, after = chapterOpening.id))
    yield
      assertEquals(unavailable, AddResult.Failed)
      assertEquals(wrong, AddResult.Failed)

  test("should swap a photo spread with its chapter neighbour in either direction"):
    val refs = Refs()

    for
      manager <- buildManager(refs)
      earlier <- manager.move(album.id, second.id, Direction.Earlier)
      later   <- manager.move(album.id, first.id, Direction.Later)
      orders  <- refs.orders.get
    yield
      val swapped = (chapter.id, NonEmptyList.of(chapterOpening.id, second.id, first.id))
      assertEquals(earlier, MoveResult.Moved(second.id))
      assertEquals(later, MoveResult.Moved(first.id))
      assertEquals(orders, Vector(swapped, swapped))

  test("should keep unrelated spreads in place when a photo spread swaps with a later neighbour"):
    val refs   = Refs()
    val longer = withChapterSpreads(Vector(first, second, third))

    for
      manager <- buildManager(refs, current = longer.some)
      result  <- manager.move(longer.id, second.id, Direction.Later)
      orders  <- refs.orders.get
    yield
      assertEquals(result, MoveResult.Moved(second.id))
      assertEquals(orders, Vector((chapter.id, NonEmptyList.of(chapterOpening.id, first.id, third.id, second.id))))

  test("should reject moving openings and photo spreads without a neighbour in their chapter"):
    val refs = Refs()

    for
      albumOpeningMove   <- buildManager(refs).flatMap(_.move(album.id, albumOpening.id, Direction.Later))
      chapterOpeningMove <- buildManager(refs).flatMap(_.move(album.id, chapterOpening.id, Direction.Earlier))
      firstEarlier       <- buildManager(refs).flatMap(_.move(album.id, first.id, Direction.Earlier))
      lastLater          <- buildManager(refs).flatMap(_.move(album.id, second.id, Direction.Later))
      orders             <- refs.orders.get
    yield
      assertEquals(albumOpeningMove, MoveResult.Rejected(Conflict.OpeningMove))
      assertEquals(chapterOpeningMove, MoveResult.Rejected(Conflict.OpeningMove))
      assertEquals(firstEarlier, MoveResult.Rejected(Conflict.NoNeighbour))
      assertEquals(lastLater, MoveResult.Rejected(Conflict.NoNeighbour))
      assertEquals(orders, Vector.empty[(ChapterId, NonEmptyList[SpreadId])])

  test("should report unknown album and spread targets when moving"):
    val refs = Refs()

    for
      missingAlbum <- buildManager(refs, current = Option.empty[Album]).flatMap(_.move(album.id, first.id, Direction.Later))
      unknown      <- buildManager(refs).flatMap(_.move(album.id, SpreadId(buildUuid(index = 999L)), Direction.Later))
      foreign      <- buildManager(refs).flatMap(_.move(album.id, foreign.id, Direction.Earlier))
    yield
      assertEquals(missingAlbum, MoveResult.AlbumNotFound)
      assertEquals(unknown, MoveResult.SpreadNotFound)
      assertEquals(foreign, MoveResult.Rejected(Conflict.NoNeighbour))

  test("should change an unoccupied photo spread to a same-context layout with fresh slots"):
    val refs = Refs()

    for
      manager      <- buildManager(refs)
      result       <- manager.changeTemplate(album.id, first.id, singleLayout, confirmPlacements = false)
      replacements <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Changed(first.id, singleLayout))
      replacements match
        case Vector(replaced: Spread.PhotosOnly) =>
          assertEquals(replaced.template, singleLayout)
          assertEquals(replaced.photoSlots.map(_.box).toList, List(box))
          assertEquals(replaced.placements, Vector.empty[SpreadPlacement])
          assert(!replaced.photoSlots.toList.exists(slot => first.photoSlots.toList.exists(_.id.value === slot.id.value)))
        case other => fail(s"Expected one replaced photo spread, obtained $other")

  test("should warn with the placement count and change nothing until an occupied template change is confirmed"):
    val refs     = Refs()
    val occupied = withChapterSpreads(Vector(firstOccupied, second))

    for
      manager  <- buildManager(refs, current = occupied.some)
      result   <- manager.changeTemplate(occupied.id, first.id, singleLayout, confirmPlacements = false)
      replaced <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Occupied(2))
      assertEquals(replaced, Vector.empty[Spread])

  test("should keep placements matched by photo-slot order, reset their transforms and release unmatched photos"):
    val refs     = Refs()
    val occupied = withChapterSpreads(Vector(firstOccupied, second))

    for
      manager  <- buildManager(refs, current = occupied.some)
      result   <- manager.changeTemplate(occupied.id, first.id, singleLayout, confirmPlacements = true)
      replaced <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Changed(first.id, singleLayout))
      replaced match
        case Vector(replacement: Spread.PhotosOnly) =>
          val retained = replacement.placements.map(placed => (placed.id, placed.photo, placed.captionCorner, placed.transform))
          assertEquals(retained, Vector((SpreadPlacementId(buildUuid(70L)), PhotoId(buildUuid(570L)), CaptionCorner.TopRight, untouched)))
          assertEquals(replacement.placements.map(_.slot).toList, replacement.photoSlots.map(_.id).toList)
        case other => fail(s"Expected one replaced photo spread, obtained $other")

  test("should reject a template from another spread context without changing the spread"):
    val refs = Refs()

    for
      photo    <- buildManager(refs).flatMap(_.changeTemplate(album.id, first.id, chapterLayout, confirmPlacements = false))
      opening  <- buildManager(refs).flatMap(_.changeTemplate(album.id, chapterOpening.id, singleLayout, confirmPlacements = false))
      replaced <- refs.replacements.get
    yield
      assertEquals(photo, ChangeTemplateResult.Rejected(Conflict.ContextMismatch))
      assertEquals(opening, ChangeTemplateResult.Rejected(Conflict.ContextMismatch))
      assertEquals(replaced, Vector.empty[Spread])

  test("should reject unknown templates and unknown spreads when changing templates"):
    val refs = Refs()

    for
      unknownTemplate <- buildManager(refs, outcome = FindTemplateResult.NotFound.some)
        .flatMap(_.changeTemplate(album.id, first.id, singleLayout, confirmPlacements = false))
      unknownSpread <-
        buildManager(refs).flatMap(_.changeTemplate(album.id, SpreadId(buildUuid(index = 999L)), singleLayout, confirmPlacements = false))
      missingAlbum <-
        buildManager(refs, current = Option.empty[Album]).flatMap(_.changeTemplate(album.id, first.id, singleLayout, confirmPlacements = false))
    yield
      assertEquals(unknownTemplate, ChangeTemplateResult.Rejected(Conflict.UnknownTemplate))
      assertEquals(unknownSpread, ChangeTemplateResult.SpreadNotFound)
      assertEquals(missingAlbum, ChangeTemplateResult.AlbumNotFound)

  test("should replace a chapter opening's photo and text slots when its template changes"):
    val refs = Refs()

    for
      manager  <- buildManager(refs)
      result   <- manager.changeTemplate(album.id, chapterOpening.id, altLayout, confirmPlacements = false)
      replaced <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Changed(chapterOpening.id, altLayout))
      replaced match
        case Vector(opening: Spread.ChapterStart) =>
          assertEquals(opening.template, altLayout)
          assertEquals(opening.photoSlots.map(_.box), Vector(box))
          assertEquals(opening.textSlot.box, wideBox)
        case other => fail(s"Expected one replaced chapter opening, obtained $other")

  test("should report a failed template lookup as Failed without changing the spread"):
    val refs = Refs()

    for
      manager  <- buildManager(refs, outcome = FindTemplateResult.Failed.some)
      result   <- manager.changeTemplate(album.id, first.id, singleLayout, confirmPlacements = false)
      replaced <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Failed)
      assertEquals(replaced, Vector.empty[Spread])

  test("should replace the album opening's photo and text slots when its synopsis layout changes"):
    val refs = Refs()

    for
      manager  <- buildManager(refs)
      result   <- manager.changeTemplate(album.id, albumOpening.id, altAlbumLayout, confirmPlacements = false)
      replaced <- refs.replacements.get
    yield
      assertEquals(result, ChangeTemplateResult.Changed(albumOpening.id, altAlbumLayout))
      replaced match
        case Vector(opening: Spread.AlbumStart) =>
          assertEquals(opening.template, altAlbumLayout)
          assertEquals(opening.photoSlots.map(_.box), Vector(box, wideBox))
          assertEquals(opening.textSlot.box, box)
        case other => fail(s"Expected one replaced album opening, obtained $other")

  test("should delete an unoccupied photo spread and renumber the remaining chapter spreads"):
    val refs = Refs()

    for
      manager <- buildManager(refs)
      result  <- manager.delete(album.id, first.id, confirmPlacements = false)
      removed <- refs.removals.get
      orders  <- refs.orders.get
    yield
      assertEquals(result, DeleteResult.Deleted)
      assertEquals(removed, Vector(first.id))
      assertEquals(orders, Vector((chapter.id, NonEmptyList.of(chapterOpening.id, second.id))))

  test("should warn with the placement count and delete nothing until an occupied delete is confirmed"):
    val refs     = Refs()
    val occupied = withChapterSpreads(Vector(firstOccupied, second))

    for
      unconfirmed   <- buildManager(refs, current = occupied.some).flatMap(_.delete(occupied.id, first.id, confirmPlacements = false))
      removedBefore <- refs.removals.get
      confirmed     <- buildManager(refs, current = occupied.some).flatMap(_.delete(occupied.id, first.id, confirmPlacements = true))
      removed       <- refs.removals.get
    yield
      assertEquals(unconfirmed, DeleteResult.Occupied(2))
      assertEquals(removedBefore, Vector.empty[SpreadId])
      assertEquals(confirmed, DeleteResult.Deleted)
      assertEquals(removed, Vector(first.id))

  test("should reject deleting either opening and report unknown spreads when deleting"):
    val refs = Refs()

    for
      chapterOpeningDelete <- buildManager(refs).flatMap(_.delete(album.id, chapterOpening.id, confirmPlacements = false))
      albumOpeningDelete   <- buildManager(refs).flatMap(_.delete(album.id, albumOpening.id, confirmPlacements = false))
      unknown              <- buildManager(refs).flatMap(_.delete(album.id, SpreadId(buildUuid(index = 999L)), confirmPlacements = false))
    yield
      assertEquals(chapterOpeningDelete, DeleteResult.Rejected(Conflict.OpeningDelete))
      assertEquals(albumOpeningDelete, DeleteResult.Rejected(Conflict.OpeningDelete))
      assertEquals(unknown, DeleteResult.SpreadNotFound)

  test("should report an unknown album when deleting"):
    val refs = Refs()

    for
      missing <- buildManager(refs, current = Option.empty[Album]).flatMap(_.delete(album.id, first.id, confirmPlacements = false))
      removed <- refs.removals.get
    yield
      assertEquals(missing, DeleteResult.AlbumNotFound)
      assertEquals(removed, Vector.empty[SpreadId])

  test("should report album read and write failures as Failed"):
    val refs    = Refs()
    val failure = new IllegalStateException("Spread store unavailable")

    for
      readFailed  <- buildManager(refs, loadError = failure.some).flatMap(manager => manager.delete(album.id, first.id, confirmPlacements = false))
      writeFailed <- buildManager(refs, writeError = failure.some).flatMap(manager => manager.delete(album.id, first.id, confirmPlacements = false))
      removed     <- refs.removals.get
    yield
      assertEquals(readFailed, DeleteResult.Failed)
      assertEquals(writeFailed, DeleteResult.Failed)
      assertEquals(removed, Vector(first.id))

  private def inserted(values: Vector[(ChapterId, Spread.PhotosOnly)]) =
    values.map { case (chapterId, spread) => (chapterId, spread.id, spread.template, spread.photoSlots.map(_.box).toList, spread.placements) }

  private def withChapterSpreads(spreads: Vector[Spread.PhotosOnly]) =
    album.copy(chapters = Vector(chapter.copy(spreads = spreads), otherChapter))

  private def photoSlot(index: Long, slotBox: SlotBox) =
    SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index)), slotBox, bleed = Set.empty[SpreadEdge])

  private def photoSpread(index: Long, placements: Vector[SpreadPlacement]) =
    val slots = NonEmptyList.of(box, wideBox).zipWithIndex.map { case (slotBox, position) =>
      photoSlot(index = index * 100L + position.toLong, slotBox)
    }
    Spread.PhotosOnly(SpreadId(buildUuid(index)), defaultLayout, photoSlots = slots, placements = placements)

  private def placement(index: Long, slot: PhotoSpreadSlotId) =
    SpreadPlacement(
      SpreadPlacementId(buildUuid(index)),
      slot,
      PhotoId(buildUuid(index + 500L)),
      PhotoTransform(Shift(x = 3.0, y = 4.0), mirrored = true, zoom = 2.0),
      CaptionCorner.TopRight
    )

  final private case class Refs():
    val inserts: Ref[IO, Vector[(ChapterId, Spread.PhotosOnly)]]     = Ref.unsafe(Vector.empty)
    val replacements: Ref[IO, Vector[Spread]]                        = Ref.unsafe(Vector.empty)
    val orders: Ref[IO, Vector[(ChapterId, NonEmptyList[SpreadId])]] = Ref.unsafe(Vector.empty)
    val removals: Ref[IO, Vector[SpreadId]]                          = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      current: Option[Album] = album.some,
      loadError: Option[Throwable] = None,
      writeError: Option[Throwable] = None,
      outcome: Option[FindTemplateResult] = None
  ) =
    given IdGenerator[IO] with
      private val next                  = Ref.unsafe[IO, Long](1000L)
      override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(buildUuid)
    val templates = new SpreadTemplateManager[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateManager.ListAlbumStartTemplatesResult] =
        IO.raiseError[SpreadTemplateManager.ListAlbumStartTemplatesResult](new AssertionError("Unexpected album templates"))
      override def listChapterStartTemplates: IO[SpreadTemplateManager.ListChapterStartTemplatesResult] =
        IO.raiseError[SpreadTemplateManager.ListChapterStartTemplatesResult](new AssertionError("Unexpected chapter templates"))
      override def listPhotosOnlyTemplates: IO[SpreadTemplateManager.ListPhotosOnlyTemplatesResult] =
        IO.raiseError[SpreadTemplateManager.ListPhotosOnlyTemplatesResult](new AssertionError("Unexpected photo templates"))
      override def findTemplate(id: SpreadTemplateId): IO[FindTemplateResult] = outcome.getOrElse(resolveCatalog(id)).pure[IO]
    val writes     = writeError.fold(IO.unit)(error => IO.raiseError[Unit](error))
    val repository = new SpreadRepository[IO]:
      override def loadAlbum(value: AlbumId): IO[Option[Album]] =
        loadError.fold(current.pure[IO])(error => IO.raiseError[Option[Album]](error))
      override def insert(chapterId: ChapterId, spread: Spread.PhotosOnly): IO[Unit] =
        refs.inserts.update(_ :+ (chapterId, spread)) >> writes
      override def replace(spread: Spread): IO[Unit]                                      = refs.replacements.update(_ :+ spread) >> writes
      override def reorder(chapterId: ChapterId, order: NonEmptyList[SpreadId]): IO[Unit] =
        refs.orders.update(_ :+ (chapterId, order)) >> writes
      override def remove(spread: SpreadId): IO[Unit] = refs.removals.update(_ :+ spread) >> writes
    Mutex[IO].map: mutex =>
      given Mutex[IO] = mutex
      given Transaction[IO, IO] with
        override def run[A](program: IO[A]): IO[A]     = program
        override def resolve[A](program: IO[A]): IO[A] = program
      SpreadManager.make(templates, repository, defaultLayout)
  // scalafix:on DisableSyntax.defaultArgs

  private def resolveCatalog(id: SpreadTemplateId) =
    catalog.find(_.id.value === id.value).fold[FindTemplateResult](FindTemplateResult.NotFound)(FindTemplateResult.Found(_))

  private def buildUuid(index: Long) = new UUID(0L, index)
