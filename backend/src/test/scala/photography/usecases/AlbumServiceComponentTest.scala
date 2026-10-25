package photography.usecases

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
import photography.usecases.AlbumService.*
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

import java.time.Instant
import java.util.UUID

final class AlbumServiceComponentTest extends CatsEffectSuite:

  private val pageSize                = 6.refineUnsafe[Positive]
  private val createdAt               = Instant.parse("2026-10-07T12:00:00Z")
  private val fullBox                 = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)
  private val photoBox                = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 0.5.refineUnsafe, 1.0.refineUnsafe)
  private val selectedYear: AlbumYear = 2026
  private val thirteenAlbums          = Vector.tabulate(13)(buildAlbum).reverse

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
      SpreadTemplateId(buildUuid(20)),
      Vector(SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(12)), photoBox, bleed = Set.empty[SpreadEdge])),
      SpreadSlot.Text(TextSpreadSlotId(buildUuid(13)), fullBox),
      Vector.empty[SpreadPlacement]
    )
    val expectedAlbum =
      Album(
        AlbumId(buildUuid(10)),
        selectedYear,
        synopsis = "",
        Instant.parse("2026-10-07T12:00:00.123Z"),
        expectedStart,
        chapters = Vector.empty[Chapter]
      )
    val expectedCover =
      Cover(AlbumId(buildUuid(10)), SpineOwner.Front, CoverSlot(CoverSlotId(buildUuid(14))), CoverSlot(CoverSlotId(buildUuid(15))))

    for
      result  <- buildService(refs, listResult = listed, currentTime = suppliedTime).create(selectedYear)
      created <- refs.maybeCreated.get
    yield
      assertEquals(result, CreateAlbumResult.Created(expectedAlbum))
      assertEquals(created, (expectedAlbum, expectedCover).some)

  test("should report failure when the atomic creation write fails"):
    buildService(Refs(), createResult = AlbumRepository.CreateResult.Failed(Failure.DatabaseFailure))
      .create(selectedYear)
      .map(assertEquals(_, CreateAlbumResult.Failed))

  test("should reject creation without writing when the AlbumStart template listing fails"):
    val refs = Refs()
    for
      result  <- buildService(refs, listResult = ListAlbumStartTemplatesResult.Failed).create(selectedYear)
      created <- refs.maybeCreated.get
    yield
      assertEquals(result, CreateAlbumResult.Failed)
      assertEquals(created, None)

  test("should return the Album by id, report not found, and report failure when lookup fails"):
    val album = buildAlbum(2)
    val refs  = Refs()

    for
      found    <- buildService(Refs(), getResult = AlbumRepository.GetResult.Found(album)).get(AlbumId(buildUuid(2)))
      missing  <- buildService(refs, getResult = AlbumRepository.GetResult.NotFound).get(AlbumId(buildUuid(1)))
      lastRead <- refs.maybeLastReadId.get
      failed   <- buildService(Refs(), getResult = AlbumRepository.GetResult.Failed(Failure.DatabaseFailure)).get(AlbumId(buildUuid(1)))
    yield
      assertEquals(found, GetAlbumResult.Found(album))
      assertEquals(missing, GetAlbumResult.NotFound)
      assertEquals(lastRead, AlbumId(buildUuid(1)).some)
      assertEquals(failed, GetAlbumResult.Failed)

  test("should report an occupied year as a creation conflict rather than a service failure"):
    val service = buildService(Refs(), createResult = AlbumRepository.CreateResult.Failed(Failure.YearAlreadyUsed))

    service.create(selectedYear).map(assertEquals(_, CreateAlbumResult.YearAlreadyUsed))

  test("should report the year change, a conflict, a missing album or a persistence failure"):
    val refs  = Refs()
    val album = buildAlbum(1).copy(year = selectedYear)

    val cases: Vector[(response: AlbumRepository.ChangeYearResult, expected: ChangeYearResult)] = Vector(
      AlbumRepository.ChangeYearResult.Changed(album)                  -> ChangeYearResult.Changed(album),
      AlbumRepository.ChangeYearResult.HasChapters                     -> ChangeYearResult.HasChapters,
      AlbumRepository.ChangeYearResult.NotFound                        -> ChangeYearResult.NotFound,
      AlbumRepository.ChangeYearResult.Failed(Failure.YearAlreadyUsed) -> ChangeYearResult.YearAlreadyUsed,
      AlbumRepository.ChangeYearResult.Failed(Failure.DatabaseFailure) -> ChangeYearResult.Failed
    )

    for
      results <- cases.traverse(c => buildService(refs, changeYearResult = c.response).changeYear(album.id, selectedYear))
      changed <- refs.maybeChangedYear.get
    yield
      assertEquals(results, cases.map(_.expected))
      assertEquals(changed, selectedYear.some)

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
    val listCalls: Ref[IO, Vector[(Int, Int)]]        = Ref.unsafe(Vector.empty)
    val maybeCreated: Ref[IO, Option[(Album, Cover)]] = Ref.unsafe(None)
    val maybeLastReadId: Ref[IO, Option[AlbumId]]     = Ref.unsafe(None)
    val maybeChangedYear: Ref[IO, Option[AlbumYear]]  = Ref.unsafe(None)
    val maybeDeletedId: Ref[IO, Option[AlbumId]]      = Ref.unsafe(None)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildService(
      refs: Refs,
      albums: Vector[Album] = Vector.empty[Album],
      maybeListFailureOffset: Option[Int] = None,
      createResult: AlbumRepository.CreateResult = AlbumRepository.CreateResult.Created,
      getResult: AlbumRepository.GetResult = AlbumRepository.GetResult.NotFound,
      changeYearResult: AlbumRepository.ChangeYearResult = AlbumRepository.ChangeYearResult.NotFound,
      deleteResult: AlbumRepository.DeleteResult = AlbumRepository.DeleteResult.NotFound,
      listResult: ListAlbumStartTemplatesResult =
        ListAlbumStartTemplatesResult.Listed(NonEmptyList.of(buildAlbumStartTemplate(20), buildAlbumStartTemplate(21))),
      currentTime: Instant = createdAt
  ) =
    val spreads = new SpreadTemplateService[IO]:
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult]                           = listResult.pure[IO]
      override def listChapterStartTemplates: IO[SpreadTemplateService.ListChapterStartTemplatesResult] =
        SpreadTemplateService.ListChapterStartTemplatesResult.Failed.pure[IO]
    given IdGenerator[IO] with
      private val nextValue         = Ref.unsafe[IO, Int](10)
      override def nextId: IO[UUID] = nextValue.getAndUpdate(_ + 1).map(buildUuid)
    given Clock[IO] with
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

      override def changeYear(id: AlbumId, year: AlbumYear): IO[AlbumRepository.ChangeYearResult] =
        refs.maybeChangedYear.set(year.some).as(changeYearResult)

      override def delete(id: AlbumId): IO[AlbumRepository.DeleteResult] =
        refs.maybeDeletedId.set(id.some).as(deleteResult)
    AlbumService.make(repository, spreads, pageSize)
  // scalafix:on DisableSyntax.defaultArgs

  private def buildAlbumStartTemplate(index: Int) =
    SpreadTemplate.AlbumStart(
      SpreadTemplateId(buildUuid(index)),
      s"Template $index".refineUnsafe[Not[Empty]],
      Vector(photoBox),
      fullBox
    )

  private def buildAlbum(index: Int) =
    val albumId = AlbumId(buildUuid(index))
    val start   = Spread.AlbumStart(
      SpreadId(buildUuid(index + 100)),
      SpreadTemplateId(buildUuid(20)),
      Vector(SpreadSlot.Photo(PhotoSpreadSlotId(buildUuid(index + 200)), photoBox, Set.empty)),
      SpreadSlot.Text(TextSpreadSlotId(buildUuid(index + 300)), fullBox),
      Vector.empty
    )
    Album(
      albumId,
      (1900 + index).refineUnsafe[AlbumYearConstraint],
      s"Synopsis $index",
      createdAt.plusSeconds(index.toLong),
      start,
      chapters = Vector.empty[Chapter]
    )

  private def buildUuid(index: Int) =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")
