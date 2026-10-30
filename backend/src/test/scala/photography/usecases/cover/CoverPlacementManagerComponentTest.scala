package photography.usecases.cover

import cats.effect.{IO, Ref}
import cats.effect.std.Mutex
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.{AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.cover.CoverPlacementRepository
import photography.ports.cover.CoverPlacementRepository.Composition
import photography.ports.photo.PlacementCollectionRepository
import photography.usecases.cover.CoverPlacementManager.*
import java.util.UUID

class CoverPlacementManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val cover        = Cover(
    AlbumId(new UUID(0L, 1L)),
    SpineOwner.Back,
    CoverSlot(CoverSlotId(new UUID(0L, 2L))),
    CoverSlot(CoverSlotId(new UUID(0L, 3L)))
  )
  private val photo   = PhotoId(new UUID(0L, 4L))
  private val foreign = PhotoId(new UUID(0L, 5L))
  private val fresh   = CoverPlacementId(new UUID(0L, 6L))
  private val initial = PhotoTransform(Shift(x = 0.0, y = 0.0), mirrored = false, zoom = 1.0)
  private val empty   = Composition(cover, placements = Vector.empty[CoverPlacement])
  private val placed  = Composition(cover, placements = Vector(CoverPlacement(fresh, cover.front.id, photo, initial)))

  test("should save and read cover placements without changing cover ownership"):
    for
      state   <- Ref.of[IO, Composition](empty)
      manager <- buildManager(state)
      saved   <- manager.edit(cover.album, Edit.Place(cover.front.id, photo))
      read    <- manager.read(cover.album)
    yield
      assertEquals(saved, Result.Saved(placed))
      assertEquals(read, ReadResult.Found(placed))

  test("should reject photographs outside the album and photographs used on any composition"):
    val requests = Vector(photo -> Failure.PhotoAlreadyUsed, foreign -> Failure.PhotoNotInCollection)

    requests.traverse_ { case (requested, failure) =>
      for
        state    <- Ref.of[IO, Composition](empty)
        manager  <- buildManager(state, usedElsewhere = Set(photo))
        rejected <- manager.edit(cover.album, Edit.Place(cover.front.id, requested))
        current  <- state.get
      yield
        assertEquals(rejected, Result.Rejected(failure))
        assertEquals(current, empty)
    }

  test("should distinguish a missing cover from failed persistence without empty success"):
    val absent = AlbumId(new UUID(0L, 99L))

    for
      state       <- Ref.of[IO, Composition](empty)
      manager     <- buildManager(state)
      broken      <- buildManager(state, readFails = true)
      failed      <- buildManager(state, writeFails = true)
      absentRead  <- manager.read(absent)
      absentWrite <- manager.edit(absent, Edit.Place(cover.front.id, photo))
      failedRead  <- broken.read(cover.album)
      failedEdit  <- broken.edit(cover.album, Edit.Place(cover.front.id, photo))
      failedSave  <- failed.edit(cover.album, Edit.Place(cover.front.id, photo))
      current     <- state.get
    yield
      assertEquals(absentRead, ReadResult.CoverNotFound)
      assertEquals(absentWrite, Result.CoverNotFound)
      assertEquals(failedRead, ReadResult.Failed)
      assertEquals(failedEdit, Result.Failed)
      assertEquals(failedSave, Result.Failed)
      assertEquals(current, empty)

  test("should serialize concurrent attempts to place a photograph on opposite cover sides"):
    for
      state    <- Ref.of[IO, Composition](empty)
      manager  <- buildManager(state)
      outcomes <- Vector(cover.front.id, cover.back.id).parTraverse(slot => manager.edit(cover.album, Edit.Place(slot, photo)))
      current  <- state.get
    yield
      assertEquals(outcomes.count { case Result.Saved(_) => true; case _ => false }, 1)
      assertEquals(outcomes.count { case Result.Rejected(Failure.PhotoAlreadyUsed) => true; case _ => false }, 1)
      assertEquals(current.placements.map(_.photo), Vector(photo))

  test("should prepare editing requests and persist the editor's complete outcome"):
    val requests = Vector(
      Edit.Place(cover.front.id, photo) -> CoverPlacementEditor.Edit.Place(cover.front.id, photo, fresh),
      Edit.Remove(fresh)                -> CoverPlacementEditor.Edit.Remove(fresh),
      Edit.Adjust(fresh, initial)       -> CoverPlacementEditor.Edit.Adjust(fresh, initial),
      Edit.Move(fresh, cover.back.id)   -> CoverPlacementEditor.Edit.Move(fresh, cover.back.id)
    )

    requests.traverse_ { case (request, prepared) =>
      for
        state   <- Ref.of[IO, Composition](empty)
        manager <- buildManager(state, maybeExpectedEdit = prepared.some)
        result  <- manager.edit(cover.album, request)
        saved   <- state.get
      yield
        assertEquals(result, Result.Saved(placed))
        assertEquals(saved, placed)
    }

  test("should translate editor rejections without writing"):
    val failures = Vector(
      CoverPlacementEditor.Failure.SlotNotFound      -> Failure.SlotNotFound,
      CoverPlacementEditor.Failure.PlacementNotFound -> Failure.PlacementNotFound,
      CoverPlacementEditor.Failure.InvalidTransform  -> Failure.InvalidTransform
    )

    failures.traverse_ { case (reason, failure) =>
      for
        state   <- Ref.of[IO, Composition](empty)
        manager <- buildManager(state, editorResult = CoverPlacementEditor.Result.Rejected(reason))
        result  <- manager.edit(cover.album, Edit.Place(cover.front.id, photo))
        saved   <- state.get
      yield
        assertEquals(result, Result.Rejected(failure))
        assertEquals(saved, empty)
    }

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      state: Ref[IO, Composition],
      usedElsewhere: Set[PhotoId] = Set.empty[PhotoId],
      readFails: Boolean = false,
      writeFails: Boolean = false,
      editorResult: CoverPlacementEditor.Result = CoverPlacementEditor.Result.Changed(placements = placed.placements),
      maybeExpectedEdit: Option[CoverPlacementEditor.Edit] = none[CoverPlacementEditor.Edit]
  ) =
    val repository = new CoverPlacementRepository[IO]:
      override def read(album: AlbumId): IO[Option[Composition]] =
        if readFails then IO.raiseError[Option[Composition]](new IllegalStateException("read failed"))
        else state.get.map(value => Option.when(album.value === value.cover.album.value)(value))
      override def replace(album: AlbumId, placements: Vector[CoverPlacement]): IO[Unit] =
        if writeFails then IO.raiseError[Unit](new IllegalStateException("write failed"))
        else state.update(_.copy(placements = placements))
    val collection = new PlacementCollectionRepository[IO]:
      override def read(album: AlbumId, maybeChapter: Option[ChapterId]): IO[PlacementCollectionRepository.Inventory] =
        state.get.map: value =>
          assertEquals(album, cover.album)
          assertEquals(maybeChapter, none[ChapterId])
          PlacementCollectionRepository.Inventory(eligible = Set(photo), placed = usedElsewhere ++ value.placements.map(_.photo))
    val editor = new CoverPlacementEditor:
      override def edit(
          slots: Set[CoverSlotId],
          placements: Vector[CoverPlacement],
          request: CoverPlacementEditor.Edit
      ): CoverPlacementEditor.Result =
        assertEquals(slots, Set(cover.front.id, cover.back.id))
        assertEquals(placements, empty.placements)
        maybeExpectedEdit.foreach(expected => assertEquals(request, expected))
        editorResult
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = fresh.value.pure[IO]
    given Transaction[IO, IO] with
      override def run[A](program: IO[A]): IO[A]     = program
      override def resolve[A](program: IO[A]): IO[A] = program
    Mutex[IO].map: mutex =>
      given Mutex[IO] = mutex
      CoverPlacementManager.make[IO, IO](repository, collection, editor)
  // scalafix:on DisableSyntax.defaultArgs
