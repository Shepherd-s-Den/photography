package photography.usecases

import photography.usecases.SpreadTemplateManager

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.capabilities.{Clock, IdGenerator}
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.AlbumRepository
import photography.ports.AlbumRepository.{AlbumPageSlice, Failure}
import photography.usecases.AlbumManager.*
import photography.usecases.SpreadTemplateManager.ListAlbumStartTemplatesResult

import java.time.Instant
import java.util.UUID

final class AlbumManagerComponentTest extends CatsEffectSuite:

  private val pageSize                = 6.refineUnsafe[Positive]
  private val createdAt               = Instant.parse("2026-10-07T12:00:00Z")
  private val fullBox                 = SlotBox(x = 0.0.refineUnsafe, y = 0.0.refineUnsafe, width = 1.0.refineUnsafe, height = 1.0.refineUnsafe)
  private val photoBox                = SlotBox(x = 0.0.refineUnsafe, y = 0.0.refineUnsafe, width = 0.5.refineUnsafe, height = 1.0.refineUnsafe)
  private val selectedYear: AlbumYear = 2026
  private val thirteenAlbums          = Vector.tabulate(13)(buildAlbum).reverse

  test(s"should page albums newest-first with $pageSize per page and clamp requests to the last page"):
    val refs      = Refs()
    val manager   = buildManager(refs, albums = thirteenAlbums)
    val lastPage  = AlbumPage(Vector(buildAlbum(index = 0)), page = 3, pageSize = 6, totalAlbums = 13, totalPages = 3)
    val emptyPage = AlbumPage(albums = Vector.empty[Album], page = 1, pageSize = 6, totalAlbums = 0, totalPages = 0)

    for
      selected  <- manager.list(page = 99)
      calls     <- refs.listCalls.get
      firstPage <- manager.list(page = 1)
      empty     <- buildManager(refs).list(page = 1)
    yield
      assertEquals(selected, ListAlbumsResult.Listed(lastPage))
      assertEquals(calls, Vector(0 -> 6, 12 -> 6))
      assertEquals(firstPage, ListAlbumsResult.Listed(AlbumPage(thirteenAlbums.take(6), page = 1, pageSize = 6, totalAlbums = 13, totalPages = 3)))
      assertEquals(empty, ListAlbumsResult.Listed(emptyPage))

  test("should report failure when either album page query fails"):
    val firstPageFails    = buildManager(Refs(), maybeListFailureOffset = 0.some).list(page = 1)
    val selectedPageFails = buildManager(Refs(), albums = thirteenAlbums, maybeListFailureOffset = 12.some).list(page = 99)

    for
      first    <- firstPageFails
      selected <- selectedPageFails
    yield
      assertEquals(first, ListAlbumsResult.Failed)
      assertEquals(selected, ListAlbumsResult.Failed)

  test("should create an Album from the first AlbumStart template the repository lists"):
    val refs         = Refs()
    val suppliedTime = Instant.parse("2026-10-07T12:00:00.123456789Z")
    val listed       = ListAlbumStartTemplatesResult.Listed(NonEmptyList.of(buildAlbumStartTemplate(index = 20), buildAlbumStartTemplate(index = 21)))

    val expectedAlbumId   = AlbumId(buildUuid(index = 10))
    val templateId        = SpreadTemplateId(buildUuid(index = 20))
    val photoSlot         = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = 12)), photoBox, bleed = Set.empty[SpreadEdge])
    val textSlot          = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = 13)), fullBox)
    val emptyPlacements   = Vector.empty[SpreadPlacement]
    val expectedStart     = Spread.AlbumStart(SpreadId(buildUuid(index = 11)), templateId, Vector(photoSlot), textSlot, emptyPlacements)
    val expectedCreatedAt = Instant.parse("2026-10-07T12:00:00.123Z")
    val emptyChapters     = Vector.empty[Chapter]
    val expectedAlbum = Album(expectedAlbumId, selectedYear, title = "Asian capitals", synopsis = "", expectedCreatedAt, expectedStart, emptyChapters)
    val front         = CoverSlot(CoverSlotId(buildUuid(index = 14)))
    val back          = CoverSlot(CoverSlotId(buildUuid(index = 15)))
    val expectedCover = Cover(expectedAlbumId, SpineOwner.Front, front, back)

    for
      result  <- buildManager(refs, listResult = listed, currentTime = suppliedTime).create(year = selectedYear, title = "Asian capitals")
      created <- refs.maybeCreated.get
    yield
      assertEquals(result, CreateAlbumResult.Created(expectedAlbum))
      assertEquals(created, (expectedAlbum, expectedCover).some)

  test("should report failure when the atomic creation write fails"):
    buildManager(Refs(), createResult = AlbumRepository.CreateResult.Failed(Failure.DatabaseFailure))
      .create(year = selectedYear, title = "Asian capitals")
      .map(assertEquals(_, CreateAlbumResult.Failed))

  test("should reject creation without writing when the AlbumStart template listing fails"):
    val refs = Refs()

    for
      result  <- buildManager(refs, listResult = ListAlbumStartTemplatesResult.Failed).create(year = selectedYear, title = "Asian capitals")
      created <- refs.maybeCreated.get
    yield
      assertEquals(result, CreateAlbumResult.Failed)
      assertEquals(created, None)

  test("should return the Album by id, report not found, and report failure when lookup fails"):
    val album = buildAlbum(index = 2)
    val refs  = Refs()

    for
      found    <- buildManager(Refs(), getResult = AlbumRepository.GetResult.Found(album)).get(AlbumId(buildUuid(index = 2)))
      missing  <- buildManager(refs, getResult = AlbumRepository.GetResult.NotFound).get(AlbumId(buildUuid(index = 1)))
      lastRead <- refs.maybeLastReadId.get
      failed   <- buildManager(Refs(), getResult = AlbumRepository.GetResult.Failed(Failure.DatabaseFailure)).get(AlbumId(buildUuid(index = 1)))
    yield
      assertEquals(found, GetAlbumResult.Found(album))
      assertEquals(missing, GetAlbumResult.NotFound)
      assertEquals(lastRead, AlbumId(buildUuid(index = 1)).some)
      assertEquals(failed, GetAlbumResult.Failed)

  test("should report an occupied year as a creation conflict rather than a service failure"):
    val manager = buildManager(Refs(), createResult = AlbumRepository.CreateResult.Failed(Failure.YearAlreadyUsed))

    manager.create(year = selectedYear, title = "Asian capitals").map(assertEquals(_, CreateAlbumResult.YearAlreadyUsed))

  test("should rename an album and preserve missing and failed write outcomes"):
    val refs  = Refs()
    val album = buildAlbum(index = 1).copy(title = "Asian capitals")

    val cases: Vector[(response: AlbumRepository.RenameResult, expected: RenameResult)] = Vector(
      AlbumRepository.RenameResult.Renamed(album)                  -> RenameResult.Renamed(album),
      AlbumRepository.RenameResult.NotFound                        -> RenameResult.NotFound,
      AlbumRepository.RenameResult.Failed(Failure.DatabaseFailure) -> RenameResult.Failed
    )

    for
      results <- cases.traverse(c => buildManager(refs, renameResult = c.response).rename(album.id, title = album.title))
      changed <- refs.maybeRenamedTitle.get
    yield
      assertEquals(results, cases.map(_.expected))
      assertEquals(changed, album.title.some)

  test("should delete an existing Album by id, report not found, and report failure when persistence fails"):
    val refs        = Refs()
    val unavailable = buildManager(Refs(), deleteResult = AlbumRepository.DeleteResult.Failed(Failure.DatabaseFailure))

    for
      deleted   <- buildManager(refs, deleteResult = AlbumRepository.DeleteResult.Deleted).delete(AlbumId(buildUuid(index = 1)))
      deletedId <- refs.maybeDeletedId.get
      missing   <- buildManager(Refs()).delete(AlbumId(buildUuid(index = 1)))
      failed    <- unavailable.delete(AlbumId(buildUuid(index = 1)))
    yield
      assertEquals(deleted, DeleteAlbumResult.Deleted)
      assertEquals(deletedId, AlbumId(buildUuid(index = 1)).some)
      assertEquals(missing, DeleteAlbumResult.NotFound)
      assertEquals(failed, DeleteAlbumResult.Failed)

  final private case class Refs():
    val listCalls: Ref[IO, Vector[(Int, Int)]]         = Ref.unsafe(Vector.empty)
    val maybeCreated: Ref[IO, Option[(Album, Cover)]]  = Ref.unsafe(None)
    val maybeLastReadId: Ref[IO, Option[AlbumId]]      = Ref.unsafe(None)
    val maybeRenamedTitle: Ref[IO, Option[AlbumTitle]] = Ref.unsafe(None)
    val maybeDeletedId: Ref[IO, Option[AlbumId]]       = Ref.unsafe(None)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      albums: Vector[Album] = Vector.empty[Album],
      maybeListFailureOffset: Option[Int] = None,
      createResult: AlbumRepository.CreateResult = AlbumRepository.CreateResult.Created,
      getResult: AlbumRepository.GetResult = AlbumRepository.GetResult.NotFound,
      renameResult: AlbumRepository.RenameResult = AlbumRepository.RenameResult.NotFound,
      deleteResult: AlbumRepository.DeleteResult = AlbumRepository.DeleteResult.NotFound,
      listResult: ListAlbumStartTemplatesResult =
        ListAlbumStartTemplatesResult.Listed(NonEmptyList.of(buildAlbumStartTemplate(index = 20), buildAlbumStartTemplate(index = 21))),
      currentTime: Instant = createdAt
  ) =
    val spreads = new SpreadTemplateManager[IO]:
      override def listPhotosOnlyTemplates: IO[SpreadTemplateManager.ListPhotosOnlyTemplatesResult] =
        IO.raiseError[SpreadTemplateManager.ListPhotosOnlyTemplatesResult](new AssertionError("Unexpected photo templates"))
      override def findTemplate(id: SpreadTemplateId): IO[SpreadTemplateManager.FindTemplateResult] =
        IO.raiseError[SpreadTemplateManager.FindTemplateResult](new AssertionError("Unexpected template lookup"))
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult]                           = listResult.pure[IO]
      override def listChapterStartTemplates: IO[SpreadTemplateManager.ListChapterStartTemplatesResult] =
        SpreadTemplateManager.ListChapterStartTemplatesResult.Failed.pure[IO]
    given IdGenerator[IO] with
      private val nextValue             = Ref.unsafe[IO, Int](10)
      override def generateId: IO[UUID] = nextValue.getAndUpdate(_ + 1).map(buildUuid)
    given Clock[IO] with
      override def readInstant: IO[Instant] = currentTime.pure[IO]
    val repository = new AlbumRepository[IO]:
      override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
        refs.listCalls.update(_ :+ (offset -> limit)).as(
          if maybeListFailureOffset.contains(offset) then AlbumRepository.ListResult.Failed(Failure.DatabaseFailure)
          else AlbumRepository.ListResult.Listed(AlbumPageSlice(totalAlbums = albums.size.toLong, albums.slice(offset, offset + limit)))
        )

      override def create(album: Album, cover: Cover): IO[AlbumRepository.CreateResult] =
        refs.maybeCreated.set((album, cover).some).as(createResult)

      override def get(id: AlbumId): IO[AlbumRepository.GetResult] =
        refs.maybeLastReadId.set(id.some).as(getResult)

      override def rename(id: AlbumId, title: AlbumTitle): IO[AlbumRepository.RenameResult] =
        refs.maybeRenamedTitle.set(title.some).as(renameResult)

      override def delete(id: AlbumId): IO[AlbumRepository.DeleteResult] =
        refs.maybeDeletedId.set(id.some).as(deleteResult)
    AlbumManager.make(repository, spreads, pageSize)
  // scalafix:on DisableSyntax.defaultArgs

  private def buildAlbumStartTemplate(index: Int) =
    val name = s"Template $index".refineUnsafe[Not[Empty]]
    SpreadTemplate.AlbumStart(SpreadTemplateId(buildUuid(index = index)), name, photoSlots = Vector(photoBox), textSlot = fullBox)

  private def buildAlbum(index: Int) =
    val albumId           = AlbumId(buildUuid(index = index))
    val templateId        = SpreadTemplateId(buildUuid(index = 20))
    val photoSlot         = SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index = index + 200)), photoBox, bleed = Set.empty)
    val textSlot          = SpreadSlot.Text(TextSpreadSlotId(buildUuid(index = index + 300)), fullBox)
    val emptyPlacements   = Vector.empty[SpreadPlacement]
    val start             = Spread.AlbumStart(SpreadId(buildUuid(index = index + 100)), templateId, Vector(photoSlot), textSlot, emptyPlacements)
    val year              = (1900 + index).refineUnsafe[AlbumYearConstraint]
    val title: AlbumTitle = s"Album $index".refineUnsafe
    val albumCreatedAt    = createdAt.plusSeconds(index.toLong)
    Album(albumId, year, title, synopsis = s"Synopsis $index", albumCreatedAt, start, chapters = Vector.empty[Chapter])

  private def buildUuid(index: Int) =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")
