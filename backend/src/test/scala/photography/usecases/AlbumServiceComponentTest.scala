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

  private val createdAt      = Instant.parse("2026-10-07T12:00:00Z")
  private val fullBox        = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)
  private val partialBox     = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 0.5.refineUnsafe, 1.0.refineUnsafe)
  private val newTitle       = "Northern Coast".refineUnsafe[Not[Empty]]
  private val thirteenAlbums = Vector.tabulate(13)(buildAlbum)

  test("should page albums newest-first with six per page and clamp requests to the available pages"):
    val refs      = Refs()
    val albums    = Vector.tabulate(13)(index => buildAlbum(12 - index))
    val lastPage  = AlbumPage(Vector(buildAlbum(0)), 3, 6, 13, 3)
    val emptyPage = AlbumPage(Vector.empty[Album], 1, 6, 0, 0)
    val firstRefs = Refs()

    for
      selected  <- buildService(refs, albums = albums).list(page = 99)
      calls     <- refs.listCalls.get
      _         <- buildService(firstRefs, albums = albums).list(page = 1)
      firstCall <- firstRefs.listCalls.get
      empty     <- buildService(Refs()).list(page = 1)
    yield
      assertEquals(selected, ListAlbumsResult.Listed(lastPage))
      assertEquals(calls, Vector(0 -> 6, 12 -> 6))
      assertEquals(firstCall, Vector(0 -> 6))
      assertEquals(empty, ListAlbumsResult.Listed(emptyPage))

  test("should report failure when either album page query fails"):
    val firstPageFails    = buildService(Refs(), listFailureOffset = 0.some).list(page = 1)
    val selectedPageFails = buildService(Refs(), albums = thirteenAlbums, listFailureOffset = 12.some).list(page = 99)

    for
      first    <- firstPageFails
      selected <- selectedPageFails
    yield
      assertEquals(first, ListAlbumsResult.Failed)
      assertEquals(selected, ListAlbumsResult.Failed)

  test("should create an Album with its album-start Spread and empty Cover from the first AlbumStart template"):
    val refs          = Refs()
    val suppliedTime  = Instant.parse("2026-10-07T12:00:00.123456789Z")
    val listed        = ListAlbumStartTemplatesResult.Listed(Vector(buildAlbumStartTemplate(21), buildAlbumStartTemplate(20)))
    val expectedAlbum =
      Album(AlbumId(buildUuid(10)), newTitle, synopsis = "", Instant.parse("2026-10-07T12:00:00.123Z"))
    val expectedStart = Spread.AlbumStart(
      SpreadId(buildUuid(11)),
      AlbumId(buildUuid(10)),
      SpreadTemplateId(buildUuid(21)),
      Vector(SpreadSlot.Text(TextSpreadSlotId(buildUuid(12)), SpreadId(buildUuid(11)), fullBox)),
      Vector.empty[SpreadPlacement]
    )
    val expectedCover =
      Cover(AlbumId(buildUuid(10)), SpineOwner.Front, CoverSlot(CoverSlotId(buildUuid(13))), CoverSlot(CoverSlotId(buildUuid(14))))

    for
      result  <- buildService(refs, listResult = listed, currentTime = suppliedTime).create(newTitle)
      created <- refs.created.get
    yield
      assertEquals(result, CreateAlbumResult.Created(expectedAlbum))
      assertEquals(created, (expectedAlbum, expectedStart, expectedCover).some)

  test("should report failure when the atomic creation write fails"):
    buildService(Refs(), createResult = AlbumRepository.CreateResult.Failed(Failure.DatabaseFailure))
      .create(newTitle)
      .map(assertEquals(_, CreateAlbumResult.Failed))

  test("should reject creation without writing when no valid AlbumStart template is available"):
    val photoTemplate   = buildAlbumStartTemplate(20).copy(slots = NonEmptyList.one(SpreadTemplateSlot.Photo(fullBox)))
    val partialTemplate = buildAlbumStartTemplate(20).copy(slots = NonEmptyList.one(SpreadTemplateSlot.Text(partialBox)))
    val kindMismatch    = buildAlbumStartTemplate(20).copy(kind = SpreadTemplateKind.PhotosOnly)
    val rejectedLists   = Vector(
      ListAlbumStartTemplatesResult.Listed(Vector.empty[SpreadTemplate]),
      ListAlbumStartTemplatesResult.Failed,
      ListAlbumStartTemplatesResult.Listed(Vector(kindMismatch)),
      ListAlbumStartTemplatesResult.Listed(Vector(photoTemplate)),
      ListAlbumStartTemplatesResult.Listed(Vector(partialTemplate))
    )

    rejectedLists.traverse_ { listResult =>
      val refs = Refs()
      for
        result  <- buildService(refs, listResult = listResult).create(newTitle)
        created <- refs.created.get
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
      lastRead <- refs.lastReadId.get
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
      renamedTo <- refs.renamed.get
      missing   <- buildService(Refs()).rename(AlbumId(buildUuid(1)), newTitle)
      failed    <-
        buildService(Refs(), renameResult = AlbumRepository.RenameResult.Failed(Failure.DatabaseFailure)).rename(AlbumId(buildUuid(1)), newTitle)
    yield
      assertEquals(result, RenameAlbumResult.Renamed(renamed))
      assertEquals(renamedTo, (AlbumId(buildUuid(1)) -> newTitle).some)
      assertEquals(missing, RenameAlbumResult.NotFound)
      assertEquals(failed, RenameAlbumResult.Failed)

  test("should delete an existing Album, report not found, and report failure when persistence fails"):
    for
      deleted <- buildService(Refs(), deleteResult = AlbumRepository.DeleteResult.Deleted).delete(AlbumId(buildUuid(1)))
      missing <- buildService(Refs()).delete(AlbumId(buildUuid(1)))
      failed  <- buildService(Refs(), deleteResult = AlbumRepository.DeleteResult.Failed(Failure.DatabaseFailure)).delete(AlbumId(buildUuid(1)))
    yield
      assertEquals(deleted, DeleteAlbumResult.Deleted)
      assertEquals(missing, DeleteAlbumResult.NotFound)
      assertEquals(failed, DeleteAlbumResult.Failed)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildService(
      refs: Refs,
      albums: Vector[Album] = Vector.empty[Album],
      listFailureOffset: Option[Int] = None,
      createResult: AlbumRepository.CreateResult = AlbumRepository.CreateResult.Created,
      getResult: AlbumRepository.GetResult = AlbumRepository.GetResult.NotFound,
      renameResult: AlbumRepository.RenameResult = AlbumRepository.RenameResult.NotFound,
      deleteResult: AlbumRepository.DeleteResult = AlbumRepository.DeleteResult.NotFound,
      listResult: ListAlbumStartTemplatesResult =
        ListAlbumStartTemplatesResult.Listed(Vector(buildAlbumStartTemplate(20), buildAlbumStartTemplate(21))),
      currentTime: Instant = createdAt
  ): AlbumService[IO] =
    // scalafix:on DisableSyntax.defaultArgs
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
          if listFailureOffset.contains(offset) then AlbumRepository.ListResult.Failed(Failure.DatabaseFailure)
          else AlbumRepository.ListResult.Listed(AlbumPageSlice(albums.size.toLong, albums.slice(offset, offset + limit)))
        )

      override def create(album: Album, start: Spread.AlbumStart, cover: Cover): IO[AlbumRepository.CreateResult] =
        refs.created.set((album, start, cover).some).as(createResult)

      override def get(id: AlbumId): IO[AlbumRepository.GetResult] =
        refs.lastReadId.set(id.some).as(getResult)

      override def rename(id: AlbumId, title: AlbumName): IO[AlbumRepository.RenameResult] =
        refs.renamed.set((id -> title).some).as(renameResult)

      override def delete(id: AlbumId): IO[AlbumRepository.DeleteResult] =
        deleteResult.pure[IO]
    AlbumService.make(repository, spreads, identifiers, clock, 6.refineUnsafe[Positive])

  private def buildAlbumStartTemplate(index: Int) =
    SpreadTemplate(
      SpreadTemplateId(buildUuid(index)),
      s"Template $index".refineUnsafe[Not[Empty]],
      SpreadTemplateKind.AlbumStart,
      NonEmptyList.one(SpreadTemplateSlot.Text(fullBox))
    )

  private def buildAlbum(index: Int) =
    Album(AlbumId(buildUuid(index)), s"Album $index".refineUnsafe[Not[Empty]], s"Synopsis $index", createdAt.plusSeconds(index.toLong))

  private def buildUuid(index: Int) =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")

  final private case class Refs():
    val listCalls: Ref[IO, Vector[(Int, Int)]]                      = Ref.unsafe(Vector.empty)
    val created: Ref[IO, Option[(Album, Spread.AlbumStart, Cover)]] = Ref.unsafe(None)
    val lastReadId: Ref[IO, Option[AlbumId]]                        = Ref.unsafe(None)
    val renamed: Ref[IO, Option[(AlbumId, AlbumName)]]              = Ref.unsafe(None)
