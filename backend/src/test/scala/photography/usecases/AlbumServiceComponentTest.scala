package photography.usecases

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.capabilities.{Clock, IdGenerator}
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*
import photography.ports.AlbumRepository
import photography.ports.AlbumRepository.{AlbumPageSlice, Failure}
import photography.usecases.AlbumService.*
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

import java.time.Instant
import java.util.UUID

final class AlbumServiceComponentTest extends CatsEffectSuite:

  private val pageSize       = 6.refineUnsafe[Positive]
  private val createdAt      = Instant.parse("2026-10-07T12:00:00Z")
  private val fullBox        = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)
  private val partialBox     = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 0.5.refineUnsafe, 1.0.refineUnsafe)
  private val newTitle       = "Northern Coast".refineUnsafe[Not[Empty] & Not[Blank]]
  private val thirteenAlbums = Vector.tabulate(13)(buildAlbum).reverse

  test(s"should page albums newest-first with $pageSize per page and clamp requests to the last page"):
    val refs      = Refs()
    val service   = buildService(refs, albums = thirteenAlbums)
    val lastPage  = AlbumPage(Vector(buildAlbum(0)), 3, 6, 13, 3)
    val emptyPage = AlbumPage(Vector.empty[Album], 1, 6, 0, 0)

    for
      selected  <- service.list(page = 99)
      calls     <- refs.listCalls.get
      firstPage <- service.list(page = 1)
      empty     <- buildService(refs).list(page = 1)
    yield
      assertEquals(selected, ListAlbumsResult.Listed(lastPage))
      assertEquals(calls, Vector(0 -> 6, 12 -> 6))
      assertEquals(firstPage, ListAlbumsResult.Listed(AlbumPage(thirteenAlbums.take(6), 1, 6, 13, 3)))
      assertEquals(empty, ListAlbumsResult.Listed(emptyPage))

  test("should report failure when either album page query fails"):
    val firstPageFails    = buildService(Refs(), maybeListFailureOffset = 0.some).list(page = 1)
    val selectedPageFails = buildService(Refs(), albums = thirteenAlbums, maybeListFailureOffset = 12.some).list(page = 99)

    for
      first    <- firstPageFails
      selected <- selectedPageFails
    yield
      assertEquals(first, ListAlbumsResult.Failed)
      assertEquals(selected, ListAlbumsResult.Failed)

  test("should create an Album from the first AlbumStart template the repository lists"):
    val refs         = Refs()
    val suppliedTime = Instant.parse("2026-10-07T12:00:00.123456789Z")
    val listed       = ListAlbumStartTemplatesResult.Listed(NonEmptyList.of(buildAlbumStartTemplate(20), buildAlbumStartTemplate(21)))

    val expectedStart = Spread.AlbumStart(
      SpreadId(buildUuid(11)),
      AlbumId(buildUuid(10)),
      SpreadTemplateId(buildUuid(20)),
      Vector(SpreadSlot.Text(TextSpreadSlotId(buildUuid(12)), SpreadId(buildUuid(11)), fullBox)),
      Vector.empty[SpreadPlacement]
    )
    val expectedAlbum =
      Album(AlbumId(buildUuid(10)), newTitle, synopsis = "", Instant.parse("2026-10-07T12:00:00.123Z"), AlbumSpreads.initial(expectedStart))
    val expectedCover =
      Cover(AlbumId(buildUuid(10)), SpineOwner.Front, CoverSlot(CoverSlotId(buildUuid(13))), CoverSlot(CoverSlotId(buildUuid(14))))

    for
      result  <- buildService(refs, listResult = listed, currentTime = suppliedTime).create(newTitle)
      created <- refs.maybeCreated.get
    yield
      assertEquals(result, CreateAlbumResult.Created(expectedAlbum))
      assertEquals(created, (expectedAlbum, expectedCover).some)

  test("should report failure when the atomic creation write fails"):
    buildService(Refs(), createResult = AlbumRepository.CreateResult.Failed(Failure.DatabaseFailure))
      .create(newTitle)
      .map(assertEquals(_, CreateAlbumResult.Failed))

  test("should reject creation without writing when no valid AlbumStart template is available"):
    val photoTemplate   = buildAlbumStartTemplate(20).copy(slots = NonEmptyList.one(SpreadTemplateSlot.Photo(fullBox)))
    val partialTemplate = buildAlbumStartTemplate(20).copy(slots = NonEmptyList.one(SpreadTemplateSlot.Text(partialBox)))
    val kindMismatch    = buildAlbumStartTemplate(20).copy(kind = SpreadTemplateKind.PhotosOnly)
    val rejectedLists   = Vector(
      ListAlbumStartTemplatesResult.Failed,
      ListAlbumStartTemplatesResult.Listed(NonEmptyList.one(kindMismatch)),
      ListAlbumStartTemplatesResult.Listed(NonEmptyList.one(photoTemplate)),
      ListAlbumStartTemplatesResult.Listed(NonEmptyList.one(partialTemplate))
    )

    rejectedLists.traverse_ { listResult =>
      val refs = Refs()
      for
        result  <- buildService(refs, listResult = listResult).create(newTitle)
        created <- refs.maybeCreated.get
      yield
        assertEquals(result, CreateAlbumResult.Failed)
        assertEquals(created, None)
    }

  test("should return the Album by id, report not found, and report failure when lookup fails"):
    val album = buildAlbum(2)
    val refs  = Refs()

    for
      found    <- buildService(Refs(), getResult = AlbumRepository.GetResult.Found(album)).get(AlbumId(buildUuid(2)))
      missing  <- buildService(refs, getResult = AlbumRepository.GetResult.NotFound).get(AlbumId(buildUuid(1)))
      lastRead <- refs.maybeLastReadId.get
      failed   <- buildService(Refs(), getResult = AlbumRepository.GetResult.Failed(Failure.InvalidAlbumData)).get(AlbumId(buildUuid(1)))
    yield
      assertEquals(found, GetAlbumResult.Found(album))
      assertEquals(missing, GetAlbumResult.NotFound)
      assertEquals(lastRead, AlbumId(buildUuid(1)).some)
      assertEquals(failed, GetAlbumResult.Failed)

  test("should rename an existing Album, report not found, and report failure when persistence fails"):
    val refs    = Refs()
    val renamed = buildAlbum(1).copy(title = newTitle)

    for
      result    <- buildService(refs, renameResult = AlbumRepository.RenameResult.Renamed(renamed)).rename(AlbumId(buildUuid(1)), newTitle)
      renamedTo <- refs.maybeRenamed.get
      missing   <- buildService(Refs()).rename(AlbumId(buildUuid(1)), newTitle)
      failed    <-
        buildService(Refs(), renameResult = AlbumRepository.RenameResult.Failed(Failure.DatabaseFailure)).rename(AlbumId(buildUuid(1)), newTitle)
    yield
      assertEquals(result, RenameAlbumResult.Renamed(renamed))
      assertEquals(renamedTo, (AlbumId(buildUuid(1)) -> newTitle).some)
      assertEquals(missing, RenameAlbumResult.NotFound)
      assertEquals(failed, RenameAlbumResult.Failed)

  test("should delete an existing Album by id, report not found, and report failure when persistence fails"):
    val refs = Refs()

    for
      deleted   <- buildService(refs, deleteResult = AlbumRepository.DeleteResult.Deleted).delete(AlbumId(buildUuid(1)))
      deletedId <- refs.maybeDeletedId.get
      missing   <- buildService(Refs()).delete(AlbumId(buildUuid(1)))
      failed    <- buildService(Refs(), deleteResult = AlbumRepository.DeleteResult.Failed(Failure.DatabaseFailure)).delete(AlbumId(buildUuid(1)))
    yield
      assertEquals(deleted, DeleteAlbumResult.Deleted)
      assertEquals(deletedId, AlbumId(buildUuid(1)).some)
      assertEquals(missing, DeleteAlbumResult.NotFound)
      assertEquals(failed, DeleteAlbumResult.Failed)

  final private case class Refs():
    val listCalls: Ref[IO, Vector[(Int, Int)]]              = Ref.unsafe(Vector.empty)
    val maybeCreated: Ref[IO, Option[(Album, Cover)]]       = Ref.unsafe(None)
    val maybeLastReadId: Ref[IO, Option[AlbumId]]           = Ref.unsafe(None)
    val maybeRenamed: Ref[IO, Option[(AlbumId, AlbumName)]] = Ref.unsafe(None)
    val maybeDeletedId: Ref[IO, Option[AlbumId]]            = Ref.unsafe(None)

  // Test builder defaults keep each scenario to the one argument it overrides.
  // scalafix:off DisableSyntax.defaultArgs
  private def buildService(
      refs: Refs,
      albums: Vector[Album] = Vector.empty[Album],
      maybeListFailureOffset: Option[Int] = None,
      createResult: AlbumRepository.CreateResult = AlbumRepository.CreateResult.Created,
      getResult: AlbumRepository.GetResult = AlbumRepository.GetResult.NotFound,
      renameResult: AlbumRepository.RenameResult = AlbumRepository.RenameResult.NotFound,
      deleteResult: AlbumRepository.DeleteResult = AlbumRepository.DeleteResult.NotFound,
      listResult: ListAlbumStartTemplatesResult =
        ListAlbumStartTemplatesResult.Listed(NonEmptyList.of(buildAlbumStartTemplate(20), buildAlbumStartTemplate(21))),
      currentTime: Instant = createdAt
  ) =
    val spreads = new SpreadTemplateService[IO]:
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult] = listResult.pure[IO]
    val identifiers = new IdGenerator[IO]:
      private val nextValue         = Ref.unsafe[IO, Int](10)
      override def nextId: IO[UUID] = nextValue.getAndUpdate(_ + 1).map(buildUuid)
    val clock = new Clock[IO]:
      override def now: IO[Instant] = currentTime.pure[IO]
    val repository = new AlbumRepository[IO]:
      override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
        refs.listCalls.update(_ :+ (offset -> limit)).as(
          if maybeListFailureOffset.contains(offset) then AlbumRepository.ListResult.Failed(Failure.DatabaseFailure)
          else AlbumRepository.ListResult.Listed(AlbumPageSlice(albums.size.toLong, albums.slice(offset, offset + limit)))
        )

      override def create(album: Album, cover: Cover): IO[AlbumRepository.CreateResult] =
        refs.maybeCreated.set((album, cover).some).as(createResult)

      override def get(id: AlbumId): IO[AlbumRepository.GetResult] =
        refs.maybeLastReadId.set(id.some).as(getResult)

      override def rename(id: AlbumId, title: AlbumName): IO[AlbumRepository.RenameResult] =
        refs.maybeRenamed.set((id -> title).some).as(renameResult)

      override def delete(id: AlbumId): IO[AlbumRepository.DeleteResult] =
        refs.maybeDeletedId.set(id.some).as(deleteResult)
    AlbumService.make(repository, spreads, identifiers, clock, pageSize)
  // scalafix:on DisableSyntax.defaultArgs

  private def buildAlbumStartTemplate(index: Int) =
    SpreadTemplate(
      SpreadTemplateId(buildUuid(index)),
      s"Template $index".refineUnsafe[Not[Empty]],
      SpreadTemplateKind.AlbumStart,
      NonEmptyList.one(SpreadTemplateSlot.Text(fullBox))
    )

  private def buildAlbum(index: Int) =
    val albumId = AlbumId(buildUuid(index))
    val start   = Spread.AlbumStart(SpreadId(buildUuid(index + 100)), albumId, SpreadTemplateId(buildUuid(20)), Vector.empty, Vector.empty)
    Album(
      albumId,
      s"Album $index".refineUnsafe[Not[Empty] & Not[Blank]],
      s"Synopsis $index",
      createdAt.plusSeconds(index.toLong),
      AlbumSpreads.initial(start)
    )

  private def buildUuid(index: Int) =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")
