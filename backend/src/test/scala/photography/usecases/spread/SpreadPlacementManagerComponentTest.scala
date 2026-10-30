package photography.usecases.spread

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
import photography.ports.album.AlbumCompositionRepository
import photography.ports.album.AlbumCompositionRepository.Located
import photography.ports.photo.PlacementCollectionRepository
import photography.ports.spread.SpreadRepository
import photography.usecases.spread.SpreadPlacementManager.*
import java.util.UUID

class SpreadPlacementManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val album        = AlbumId(new UUID(0L, 1L))
  private val chapter      = ChapterId(new UUID(0L, 2L))
  private val photo        = PhotoId(new UUID(0L, 3L))
  private val foreign      = PhotoId(new UUID(0L, 4L))
  private val slot         = SpreadSlot.Photo(
    PhotoSpreadSlotId(new UUID(0L, 5L)),
    SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0),
    bleed = Set.empty[SpreadEdge]
  )
  private val secondSlot = slot.copy(id = PhotoSpreadSlotId(new UUID(0L, 6L)))
  private val spread     = Spread.PhotosOnly(
    SpreadId(new UUID(0L, 7L)),
    SpreadTemplateId(new UUID(0L, 8L)),
    photoSlots = NonEmptyList.of(slot, secondSlot),
    placements = Vector.empty[SpreadPlacement]
  )
  private val fresh    = SpreadPlacementId(new UUID(0L, 9L))
  private val initial  = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val expected = spread.copy(placements = Vector(SpreadPlacement(fresh, slot.id, photo, initial, CaptionCorner.BottomRight)))

  test("should save an eligible photograph and return the authoritative composition"):
    for
      state   <- Ref.of[IO, Spread](spread)
      manager <- buildManager(state)
      result  <- manager.edit(album, spread.id, Edit.Place(slot.id, photo))
      saved   <- state.get
      read    <- manager.read(album, spread.id)
    yield
      assertEquals(result, Result.Saved(expected))
      assertEquals(saved, expected)
      assertEquals(read, ReadResult.Found(expected))

  test("should reject wrong-owner and already-used photographs without writing"):
    val requests = Vector(photo -> Failure.PhotoAlreadyUsed, foreign -> Failure.PhotoNotInCollection)

    requests.traverse_ { case (requested, failure) =>
      for
        state   <- Ref.of[IO, Spread](spread)
        manager <- buildManager(state, usedElsewhere = Set(photo))
        result  <- manager.edit(album, spread.id, Edit.Place(slot.id, requested))
        saved   <- state.get
      yield
        assertEquals(result, Result.Rejected(failure))
        assertEquals(saved, spread)
    }

  test("should distinguish missing album and stale spread from failed reads"):
    val absentAlbum  = AlbumId(new UUID(0L, 90L))
    val absentSpread = SpreadId(new UUID(0L, 91L))

    for
      state         <- Ref.of[IO, Spread](spread)
      manager       <- buildManager(state)
      broken        <- buildManager(state, readFails = true)
      missingAlbum  <- manager.edit(absentAlbum, spread.id, Edit.Place(slot.id, photo))
      missingSpread <- manager.edit(album, absentSpread, Edit.Place(slot.id, photo))
      failedRead    <- broken.read(album, spread.id)
      failedWrite   <- broken.edit(album, spread.id, Edit.Place(slot.id, photo))
      saved         <- state.get
    yield
      assertEquals(missingAlbum, Result.AlbumNotFound)
      assertEquals(missingSpread, Result.SpreadNotFound)
      assertEquals(failedRead, ReadResult.Failed)
      assertEquals(failedWrite, Result.Failed)
      assertEquals(saved, spread)

  test("should preserve confirmed state when the persistence boundary fails"):
    for
      state   <- Ref.of[IO, Spread](spread)
      manager <- buildManager(state, writeFails = true)
      result  <- manager.edit(album, spread.id, Edit.Place(slot.id, photo))
      saved   <- state.get
    yield
      assertEquals(result, Result.Failed)
      assertEquals(saved, spread)

  test("should serialize concurrent placement decisions against current authoritative state"):
    for
      state    <- Ref.of[IO, Spread](spread)
      manager  <- buildManager(state)
      outcomes <- Vector(slot.id, secondSlot.id).parTraverse(destination => manager.edit(album, spread.id, Edit.Place(destination, photo)))
      saved    <- state.get
    yield
      assertEquals(outcomes.count { case Result.Saved(_) => true; case _ => false }, 1)
      assertEquals(outcomes.count { case Result.Rejected(Failure.PhotoAlreadyUsed) => true; case _ => false }, 1)
      assertEquals(saved.placements.map(_.photo), Vector(photo))

  test("should prepare editing requests and persist the editor's complete outcome"):
    val requests = Vector(
      Edit.Place(slot.id, photo)      -> SpreadPlacementEditor.Edit.Place(slot.id, photo, fresh),
      Edit.Remove(fresh)              -> SpreadPlacementEditor.Edit.Remove(fresh),
      Edit.Adjust(fresh, initial)     -> SpreadPlacementEditor.Edit.Adjust(fresh, initial),
      Edit.Move(fresh, secondSlot.id) -> SpreadPlacementEditor.Edit.Move(fresh, secondSlot.id)
    )

    requests.traverse_ { case (request, prepared) =>
      for
        state   <- Ref.of[IO, Spread](spread)
        manager <- buildManager(state, maybeExpectedEdit = prepared.some)
        result  <- manager.edit(album, spread.id, request)
        saved   <- state.get
      yield
        assertEquals(result, Result.Saved(expected))
        assertEquals(saved, expected)
    }

  test("should translate editor rejections without writing"):
    val failures = Vector(
      SpreadPlacementEditor.Failure.SlotNotFound      -> Failure.SlotNotFound,
      SpreadPlacementEditor.Failure.PlacementNotFound -> Failure.PlacementNotFound,
      SpreadPlacementEditor.Failure.InvalidTransform  -> Failure.InvalidTransform
    )

    failures.traverse_ { case (reason, failure) =>
      for
        state   <- Ref.of[IO, Spread](spread)
        manager <- buildManager(state, editorResult = SpreadPlacementEditor.Result.Rejected(reason))
        result  <- manager.edit(album, spread.id, Edit.Place(slot.id, photo))
        saved   <- state.get
      yield
        assertEquals(result, Result.Rejected(failure))
        assertEquals(saved, spread)
    }

  test("should support album-wide and chapter-opening collections and report missing read targets"):
    val text           = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 70L)), slot.box)
    val albumOpening   = Spread.AlbumStart(spread.id, spread.template, photoSlots = Vector(slot), text, placements = Vector.empty[SpreadPlacement])
    val chapterOpening = Spread.ChapterStart(spread.id, spread.template, photoSlots = Vector(slot), text, placements = Vector.empty[SpreadPlacement])
    val cases: Vector[(Spread, Spread)] = Vector(
      albumOpening   -> albumOpening.copy(placements = expected.placements),
      chapterOpening -> chapterOpening.copy(placements = expected.placements)
    )

    cases.traverse_ { case (opening, changed) =>
      for
        state         <- Ref.of[IO, Spread](opening)
        manager       <- buildManager(state, editorResult = SpreadPlacementEditor.Result.Changed(changed))
        result        <- manager.edit(album, opening.id, Edit.Place(slot.id, photo))
        missingAlbum  <- manager.read(AlbumId(new UUID(0L, 100L)), opening.id)
        missingSpread <- manager.read(album, SpreadId(new UUID(0L, 101L)))
        saved         <- state.get
      yield
        assertEquals(result, Result.Saved(saved))
        assertEquals(saved.placements, expected.placements)
        assertEquals(missingAlbum, ReadResult.AlbumNotFound)
        assertEquals(missingSpread, ReadResult.SpreadNotFound)
    }

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      state: Ref[IO, Spread],
      usedElsewhere: Set[PhotoId] = Set.empty[PhotoId],
      readFails: Boolean = false,
      writeFails: Boolean = false,
      editorResult: SpreadPlacementEditor.Result = SpreadPlacementEditor.Result.Changed(expected),
      maybeExpectedEdit: Option[SpreadPlacementEditor.Edit] = none[SpreadPlacementEditor.Edit]
  ) =
    val composition = new AlbumCompositionRepository[IO]:
      override def hasAlbum(id: AlbumId): IO[Boolean] =
        if readFails then IO.raiseError[Boolean](new IllegalStateException("read failed")) else (id.value === album.value).pure[IO]
      override def findSpread(id: AlbumId, requested: SpreadId): IO[Option[Located]] =
        state.get.map: current =>
          val located = current match
            case value: Spread.AlbumStart   => Located.AlbumOpening(value)
            case value: Spread.ChapterStart => Located.ChapterOpening(chapter, value)
            case value: Spread.PhotosOnly   => Located.Photo(chapter, value)
          Option.when(id.value === album.value && requested.value === current.id.value)(located)
      override def countInteriorSpreads(id: AlbumId): IO[Int] = IO.raiseError[Int](new AssertionError("Unexpected count"))
      override def findChapterOrder(id: AlbumId, requested: ChapterId): IO[Option[NonEmptyList[SpreadId]]] =
        IO.raiseError[Option[NonEmptyList[SpreadId]]](new AssertionError("Unexpected order"))
    val collection = new PlacementCollectionRepository[IO]:
      override def read(id: AlbumId, maybeChapter: Option[ChapterId]): IO[PlacementCollectionRepository.Inventory] =
        state.get.map: current =>
          val expectedChapter = current match
            case _: Spread.AlbumStart => none[ChapterId]
            case _                    => chapter.some
          assertEquals(id, album)
          assertEquals(maybeChapter, expectedChapter)
          PlacementCollectionRepository.Inventory(eligible = Set(photo), placed = usedElsewhere ++ current.placements.map(_.photo))
    val repository = new SpreadRepository[IO]:
      override def replace(value: Spread): IO[Unit] =
        if writeFails then IO.raiseError[Unit](new IllegalStateException("write failed")) else state.set(value)
      override def insert(id: ChapterId, value: Spread.PhotosOnly): IO[Unit]       = IO.raiseError[Unit](new AssertionError("Unexpected insert"))
      override def remove(id: SpreadId): IO[Unit]                                  = IO.raiseError[Unit](new AssertionError("Unexpected removal"))
      override def reorder(id: ChapterId, order: NonEmptyList[SpreadId]): IO[Unit] = IO.raiseError[Unit](new AssertionError("Unexpected reorder"))
    val editor = new SpreadPlacementEditor:
      override def edit(current: Spread, request: SpreadPlacementEditor.Edit): SpreadPlacementEditor.Result =
        assertEquals(current.placements, spread.placements)
        maybeExpectedEdit.foreach(expected => assertEquals(request, expected))
        editorResult
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = fresh.value.pure[IO]
    given Transaction[IO, IO] with
      override def run[A](program: IO[A]): IO[A]     = program
      override def resolve[A](program: IO[A]): IO[A] = program
    Mutex[IO].map: mutex =>
      given Mutex[IO] = mutex
      SpreadPlacementManager.make[IO, IO](composition, repository, collection, editor)
  // scalafix:on DisableSyntax.defaultArgs
