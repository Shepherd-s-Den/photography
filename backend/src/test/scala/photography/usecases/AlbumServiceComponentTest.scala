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
import photography.ports.AlbumRepository.{AlbumPageSlice, AlbumRepositoryFailure}
import photography.usecases.AlbumService.*
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

import java.time.Instant
import java.util.UUID

final class AlbumServiceComponentTest extends CatsEffectSuite:

  private val createdAt = Instant.parse("2026-10-07T12:00:00Z")

  test("should return the last Album page in newest-first order for an out-of-range request"):
    val refs     = Refs()
    val albums   = Vector.tabulate(13)(index => buildAlbum(12 - index))
    val expected = AlbumPage(Vector(buildAlbum(0)), 3, 6, 13, 3)

    for
      result <- buildService(refs, albums = albums).list(page = 99)
      calls  <- refs.listCalls.get
    yield
      assertEquals(result, ListAlbumsResult.Listed(expected))
      assertEquals(calls, Vector(0 -> 6, 12 -> 6))

  test("should return page one with empty results and zero pages"):
    val expected = AlbumPage(Vector.empty[Album], 1, 6, 0, 0)

    buildService(Refs()).list(page = 1).map(assertEquals(_, ListAlbumsResult.Listed(expected)))

  test("should not repeat the first query when the first page is selected"):
    val refs = Refs()

    for
      _     <- buildService(refs, albums = Vector.tabulate(13)(buildAlbum)).list(page = 1)
      calls <- refs.listCalls.get
    yield assertEquals(calls, Vector(0 -> 6))

  test("should report failure when the first album page query fails"):
    buildService(Refs(), listFailureOffset = 0.some).list(page = 1).map(assertEquals(_, ListAlbumsResult.Failed))

  test("should report failure when the selected album page query fails"):
    val service = buildService(Refs(), albums = Vector.tabulate(13)(buildAlbum), listFailureOffset = 12.some)
    service.list(page = 99).map(assertEquals(_, ListAlbumsResult.Failed))

  test("should create an Album, album-start Spread, and empty Cover"):
    val refs = Refs()

    for
      result  <- buildService(refs).create("Northern Coast".refineUnsafe[Not[Empty]])
      created <- refs.created.get
    yield
      val (album, start, cover) = created.getOrElse(fail("expected a created composition"))
      assertEquals(result, CreateAlbumResult.Created(album))
      assertEquals(album.title, "Northern Coast".refineUnsafe[Not[Empty]])
      assertEquals(album.synopsis, "")
      assertEquals(album.createdAt, createdAt)
      assertEquals(start.album, album.id)
      assertEquals(start.template, buildTemplateId(20))
      assertEquals(start.slots, Vector(SpreadSlot.Text(TextSpreadSlotId(buildUuid(12)), start.id, buildFullSpreadBox)))
      assertEquals(start.placements, Vector.empty[SpreadPlacement])
      assertEquals(cover.album, album.id)
      assertEquals(cover.spineOwner, SpineOwner.Front)
      assertEquals(cover.front, CoverSlot(CoverSlotId(buildUuid(13))))
      assertEquals(cover.back, CoverSlot(CoverSlotId(buildUuid(14))))

  test("should truncate creation time to the precision persisted by SQLite"):
    val suppliedTime = Instant.parse("2026-10-07T12:00:00.123456789Z")
    val expectedTime = Instant.ofEpochMilli(suppliedTime.toEpochMilli)

    buildService(Refs(), currentTime = suppliedTime).create("Album".refineUnsafe[Not[Empty]]).map {
      case CreateAlbumResult.Created(album) => assertEquals(album.createdAt, expectedTime)
      case other                            => fail(s"unexpected result: $other")
    }

  test("should report failure when atomic creation fails"):
    buildService(Refs(), createResult = AlbumRepository.CreateResult.Failed(AlbumRepositoryFailure.DatabaseFailure))
      .create("New album".refineUnsafe[Not[Empty]])
      .map(assertEquals(_, CreateAlbumResult.Failed))

  test("should report failure when album lookup fails"):
    val service = buildService(Refs(), getResult = AlbumRepository.GetResult.Failed(AlbumRepositoryFailure.InvalidAlbumData))
    service.get(buildAlbumId(1)).map(assertEquals(_, GetAlbumResult.Failed))

  test("should report not found when the requested album is missing"):
    val refs = Refs()

    for
      result   <- buildService(refs, getResult = AlbumRepository.GetResult.NotFound).get(buildAlbumId(1))
      lastRead <- refs.lastReadId.get
    yield
      assertEquals(result, GetAlbumResult.NotFound)
      assertEquals(lastRead, buildAlbumId(1).some)

  test("should return the Album when it exists"):
    val expected = buildAlbum(2)

    val service = buildService(Refs(), getResult = AlbumRepository.GetResult.Found(expected))
    service.get(buildAlbumId(2)).map(assertEquals(_, GetAlbumResult.Found(expected)))

  test("should report not found when rename targets a missing album"):
    buildService(Refs()).rename(buildAlbumId(1), "Renamed".refineUnsafe[Not[Empty]]).map(assertEquals(_, RenameAlbumResult.NotFound))

  test("should return the renamed Album"):
    val refs    = Refs()
    val renamed = buildAlbum(1).copy(title = "Renamed".refineUnsafe[Not[Empty]])

    for
      result <-
        buildService(refs, renameResult = AlbumRepository.RenameResult.Renamed(renamed)).rename(buildAlbumId(1), "Renamed".refineUnsafe[Not[Empty]])
      renamedTo <- refs.renamed.get
    yield
      assertEquals(result, RenameAlbumResult.Renamed(renamed))
      assertEquals(renamedTo, (buildAlbumId(1) -> "Renamed".refineUnsafe[Not[Empty]]).some)

  test("should report failure when rename persistence fails"):
    buildService(Refs(), renameResult = AlbumRepository.RenameResult.Failed(AlbumRepositoryFailure.DatabaseFailure))
      .rename(buildAlbumId(1), "Renamed".refineUnsafe[Not[Empty]])
      .map(assertEquals(_, RenameAlbumResult.Failed))

  test("should report not found when deletion finds no row"):
    buildService(Refs()).delete(buildAlbumId(1)).map(assertEquals(_, DeleteAlbumResult.NotFound))

  test("should delete an existing album"):
    buildService(Refs(), deleteResult = AlbumRepository.DeleteResult.Deleted).delete(buildAlbumId(1)).map(assertEquals(_, DeleteAlbumResult.Deleted))

  test("should report failure when deletion persistence fails"):
    buildService(Refs(), deleteResult = AlbumRepository.DeleteResult.Failed(AlbumRepositoryFailure.DatabaseFailure))
      .delete(buildAlbumId(1))
      .map(assertEquals(_, DeleteAlbumResult.Failed))

  test("should use the first AlbumStart template the spread service lists when creating"):
    val refs    = Refs()
    val spreads = buildSpreadTemplates(ListAlbumStartTemplatesResult.Listed(Vector(buildAlbumStartTemplate(21), buildAlbumStartTemplate(20))))

    for
      _       <- buildService(refs, spreads = spreads).create("Northern Coast".refineUnsafe[Not[Empty]])
      created <- refs.created.get
    yield assertEquals(created.map(_._2.template), buildTemplateId(21).some)

  test("should fail creation without writing when no AlbumStart template is seeded"):
    assertCreationRejectedWithoutWriting(buildSpreadTemplates(ListAlbumStartTemplatesResult.Listed(Vector.empty[SpreadTemplate])))

  test("should fail creation without writing when the template lookup fails"):
    assertCreationRejectedWithoutWriting(buildSpreadTemplates(ListAlbumStartTemplatesResult.Failed))

  test("should fail creation without writing when the first template is not an AlbumStart kind"):
    val template = buildAlbumStartTemplate(20).copy(kind = SpreadTemplateKind.PhotosOnly)

    assertCreationRejectedWithoutWriting(singleTemplate(template))

  test("should fail creation without writing when the first template has a photo slot"):
    val template = buildAlbumStartTemplate(20).copy(slots = NonEmptyList.one(SpreadTemplateSlot.Photo(buildFullSpreadBox)))

    assertCreationRejectedWithoutWriting(singleTemplate(template))

  test("should fail creation without writing when the text slot does not cover the full spread"):
    val partialBox = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 0.5.refineUnsafe, 1.0.refineUnsafe)
    val template   = buildAlbumStartTemplate(20).copy(slots = NonEmptyList.one(SpreadTemplateSlot.Text(partialBox)))

    assertCreationRejectedWithoutWriting(singleTemplate(template))

  private def assertCreationRejectedWithoutWriting(spreads: SpreadTemplateService[IO]): IO[Unit] =
    val refs = Refs()

    for
      result  <- buildService(refs, spreads = spreads).create("Northern Coast".refineUnsafe[Not[Empty]])
      created <- refs.created.get
    yield
      assertEquals(result, CreateAlbumResult.Failed)
      assertEquals(created, None)

  private def singleTemplate(template: SpreadTemplate): SpreadTemplateService[IO] =
    buildSpreadTemplates(ListAlbumStartTemplatesResult.Listed(Vector(template)))

  private def buildSpreadTemplates(listResult: ListAlbumStartTemplatesResult): SpreadTemplateService[IO] =
    new SpreadTemplateService[IO]:
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult] = IO.pure(listResult)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildService(
      refs: Refs,
      albums: Vector[Album] = Vector.empty[Album],
      listFailureOffset: Option[Int] = None,
      createResult: AlbumRepository.CreateResult = AlbumRepository.CreateResult.Created,
      getResult: AlbumRepository.GetResult = AlbumRepository.GetResult.NotFound,
      renameResult: AlbumRepository.RenameResult = AlbumRepository.RenameResult.NotFound,
      deleteResult: AlbumRepository.DeleteResult = AlbumRepository.DeleteResult.NotFound,
      spreads: SpreadTemplateService[IO] = buildSpreadTemplates(ListAlbumStartTemplatesResult.Listed(seededAlbumStartTemplates)),
      currentTime: Instant = createdAt
  ): AlbumService[IO] =
    // scalafix:on DisableSyntax.defaultArgs
    val identifiers = new IdGenerator[IO]:
      private val nextValue         = Ref.unsafe[IO, Int](10)
      override def nextId: IO[UUID] = nextValue.getAndUpdate(_ + 1).map(buildUuid)
    val clock = new Clock[IO]:
      override def now: IO[Instant] = IO.pure(currentTime)
    val repository = new AlbumRepository[IO]:
      override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
        refs.listCalls.update(_ :+ (offset -> limit)).as(
          if listFailureOffset.contains(offset) then AlbumRepository.ListResult.Failed(AlbumRepositoryFailure.DatabaseFailure)
          else AlbumRepository.ListResult.Listed(AlbumPageSlice(albums.size.toLong, albums.slice(offset, offset + limit)))
        )

      override def create(album: Album, start: Spread.AlbumStart, cover: Cover): IO[AlbumRepository.CreateResult] =
        refs.created.set((album, start, cover).some).as(createResult)

      override def get(id: AlbumId): IO[AlbumRepository.GetResult] =
        refs.lastReadId.set(id.some).as(getResult)

      override def rename(id: AlbumId, title: AlbumName): IO[AlbumRepository.RenameResult] =
        refs.renamed.set((id -> title).some).as(renameResult)

      override def delete(id: AlbumId): IO[AlbumRepository.DeleteResult] =
        IO.pure(deleteResult)
    AlbumService.make(repository, spreads, identifiers, clock, buildPageSize)

  private def buildPageSize: AlbumPageSize = 6.refineUnsafe[Positive]

  private def buildFullSpreadBox: SlotBox =
    SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)

  private def buildAlbumStartTemplate(index: Int): SpreadTemplate =
    SpreadTemplate(
      buildTemplateId(index),
      s"Template $index".refineUnsafe[Not[Empty]],
      SpreadTemplateKind.AlbumStart,
      NonEmptyList.one(SpreadTemplateSlot.Text(buildFullSpreadBox))
    )

  private def seededAlbumStartTemplates: Vector[SpreadTemplate] =
    Vector(buildAlbumStartTemplate(20), buildAlbumStartTemplate(21))

  private def buildTemplateId(index: Int): SpreadTemplateId =
    SpreadTemplateId(buildUuid(index))

  private def buildAlbum(index: Int): Album =
    Album(buildAlbumId(index), s"Album $index".refineUnsafe[Not[Empty]], s"Synopsis $index", createdAt.plusSeconds(index.toLong))

  private def buildAlbumId(index: Int): AlbumId =
    AlbumId(buildUuid(index))

  private def buildUuid(index: Int): UUID =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")

  final private case class Refs():
    val listCalls: Ref[IO, Vector[(Int, Int)]]                      = Ref.unsafe(Vector.empty)
    val created: Ref[IO, Option[(Album, Spread.AlbumStart, Cover)]] = Ref.unsafe(None)
    val lastReadId: Ref[IO, Option[AlbumId]]                        = Ref.unsafe(None)
    val renamed: Ref[IO, Option[(AlbumId, AlbumName)]]              = Ref.unsafe(None)
