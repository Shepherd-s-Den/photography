package photography.app.album

import cats.data.NonEmptySet
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.app.capabilities.{Clock, IdGenerator}
import photography.app.spread.{ListAlbumStartTemplatesResult, SpreadTemplateService}
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

final class AlbumServiceComponentTest extends CatsEffectSuite:

  private val createdAt = Instant.parse("2026-10-07T12:00:00Z")

  test("should return the last Album page in newest-first order for an out-of-range request"):
    val refs     = Refs()
    val albums   = Vector.tabulate(13)(index => buildAlbum(12 - index))
    val expected = AlbumPage(Vector(buildAlbum(0)), 3, 6, 13, 3)

    buildService(refs, RepositoryFixture.empty.copy(albums = albums)).list(page = 99).map: result =>
      assertEquals(result, ListAlbumsResult.Listed(expected))
      assertEquals(refs.listCalls.get(), Vector(0 -> 6, 12 -> 6))

  test("should return page one with empty results and zero pages"):
    val expected = AlbumPage(Vector.empty[Album], 1, 6, 0, 0)

    buildService(Refs(), RepositoryFixture.empty).list(page = 1).map(assertEquals(_, ListAlbumsResult.Listed(expected)))

  test("should not repeat the first query when the first page is selected"):
    val refs = Refs()

    buildService(refs, RepositoryFixture.empty.copy(albums = Vector.tabulate(13)(buildAlbum))).list(page = 1).map: _ =>
      assertEquals(refs.listCalls.get(), Vector(0 -> 6))

  test("should report failure when the first album page query fails"):
    buildService(Refs(), RepositoryFixture.empty.copy(listFailureOffset = 0.some)).list(page = 1).map(assertEquals(_, ListAlbumsResult.Failed))

  test("should report failure when the selected album page query fails"):
    val fixture = RepositoryFixture.empty.copy(albums = Vector.tabulate(13)(buildAlbum), listFailureOffset = 12.some)

    buildService(Refs(), fixture).list(page = 99).map(assertEquals(_, ListAlbumsResult.Failed))

  test("should create an Album, album-start Spread, and empty Cover"):
    val refs = Refs()

    buildService(refs, RepositoryFixture.empty).create("Northern Coast".refineUnsafe[Not[Empty]]).map: result =>
      val (album, start, cover) = refs.created.get().getOrElse(fail("expected a created composition"))
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

    buildServiceAt(Refs(), RepositoryFixture.empty, suppliedTime).create("Album".refineUnsafe[Not[Empty]]).map {
      case CreateAlbumResult.Created(album) => assertEquals(album.createdAt, expectedTime)
      case other                            => fail(s"unexpected result: $other")
    }

  test("should report failure when atomic creation fails"):
    val fixture = RepositoryFixture.empty.copy(createResult = AlbumRepository.CreateResult.Failed(AlbumRepositoryFailure.DatabaseFailure))

    buildService(Refs(), fixture).create("New album".refineUnsafe[Not[Empty]]).map(assertEquals(_, CreateAlbumResult.Failed))

  test("should report failure when album lookup fails"):
    val fixture = RepositoryFixture.empty.copy(getResult = AlbumRepository.GetResult.Failed(AlbumRepositoryFailure.InvalidAlbumData))

    buildService(Refs(), fixture).get(buildAlbumId(1)).map(assertEquals(_, GetAlbumResult.Failed))

  test("should report not found when the requested album is missing"):
    val refs    = Refs()
    val fixture = RepositoryFixture.empty.copy(getResult = AlbumRepository.GetResult.NotFound)

    buildService(refs, fixture).get(buildAlbumId(1)).map: result =>
      assertEquals(result, GetAlbumResult.NotFound)
      assertEquals(refs.lastReadId.get(), buildAlbumId(1).some)

  test("should return the Album when it exists"):
    val expected = buildAlbum(2)
    val fixture  = RepositoryFixture.empty.copy(getResult = AlbumRepository.GetResult.Found(expected))

    buildService(Refs(), fixture).get(buildAlbumId(2)).map(assertEquals(_, GetAlbumResult.Found(expected)))

  test("should report not found when rename targets a missing album"):
    buildService(
      Refs(),
      RepositoryFixture.empty
    ).rename(buildAlbumId(1), "Renamed".refineUnsafe[Not[Empty]]).map(assertEquals(_, RenameAlbumResult.NotFound))

  test("should return the renamed Album"):
    val refs    = Refs()
    val renamed = buildAlbum(1).copy(title = "Renamed".refineUnsafe[Not[Empty]])
    val fixture = RepositoryFixture.empty.copy(renameResult = AlbumRepository.RenameResult.Renamed(renamed))

    buildService(refs, fixture).rename(buildAlbumId(1), "Renamed".refineUnsafe[Not[Empty]]).map: result =>
      assertEquals(result, RenameAlbumResult.Renamed(renamed))
      assertEquals(refs.renamed.get(), (buildAlbumId(1) -> "Renamed".refineUnsafe[Not[Empty]]).some)

  test("should report failure when rename persistence fails"):
    val fixture = RepositoryFixture.empty.copy(renameResult = AlbumRepository.RenameResult.Failed(AlbumRepositoryFailure.DatabaseFailure))

    buildService(Refs(), fixture).rename(buildAlbumId(1), "Renamed".refineUnsafe[Not[Empty]]).map(assertEquals(_, RenameAlbumResult.Failed))

  test("should report not found when deletion finds no row"):
    buildService(Refs(), RepositoryFixture.empty).delete(buildAlbumId(1)).map(assertEquals(_, DeleteAlbumResult.NotFound))

  test("should delete an existing album"):
    val fixture = RepositoryFixture.empty.copy(deleteResult = AlbumRepository.DeleteResult.Deleted)

    buildService(Refs(), fixture).delete(buildAlbumId(1)).map(assertEquals(_, DeleteAlbumResult.Deleted))

  test("should report failure when deletion persistence fails"):
    val fixture = RepositoryFixture.empty.copy(deleteResult = AlbumRepository.DeleteResult.Failed(AlbumRepositoryFailure.DatabaseFailure))

    buildService(Refs(), fixture).delete(buildAlbumId(1)).map(assertEquals(_, DeleteAlbumResult.Failed))

  test("should use the first AlbumStart template the spread service lists when creating"):
    val refs    = Refs()
    val spreads = buildSpreadTemplates(ListAlbumStartTemplatesResult.Listed(Vector(buildAlbumStartTemplate(21), buildAlbumStartTemplate(20))))

    buildServiceWith(refs, RepositoryFixture.empty, spreads, createdAt).create("Northern Coast".refineUnsafe[Not[Empty]]).map: _ =>
      assertEquals(refs.created.get().map(_._2.template), buildTemplateId(21).some)

  test("should fail creation without writing when no AlbumStart template is seeded"):
    assertCreationRejectedWithoutWriting(buildSpreadTemplates(ListAlbumStartTemplatesResult.Listed(Vector.empty[SpreadTemplate])))

  test("should fail creation without writing when the template lookup fails"):
    assertCreationRejectedWithoutWriting(buildSpreadTemplates(ListAlbumStartTemplatesResult.Failed))

  test("should fail creation without writing when the first template is not an AlbumStart kind"):
    val template = buildAlbumStartTemplate(20).copy(kind = SpreadTemplateKind.PhotosOnly)

    assertCreationRejectedWithoutWriting(singleTemplate(template))

  test("should fail creation without writing when the first template has a photo slot"):
    val template = buildAlbumStartTemplate(20).copy(slots = NonEmptySet.one(SpreadTemplateSlot.Photo(buildFullSpreadBox)))

    assertCreationRejectedWithoutWriting(singleTemplate(template))

  test("should fail creation without writing when the text slot does not cover the full spread"):
    val partialBox = SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 0.5.refineUnsafe, 1.0.refineUnsafe)
    val template   = buildAlbumStartTemplate(20).copy(slots = NonEmptySet.one(SpreadTemplateSlot.Text(partialBox)))

    assertCreationRejectedWithoutWriting(singleTemplate(template))

  private def assertCreationRejectedWithoutWriting(spreads: SpreadTemplateService[IO]): IO[Unit] =
    val refs = Refs()

    buildServiceWith(refs, RepositoryFixture.empty, spreads, createdAt).create("Northern Coast".refineUnsafe[Not[Empty]]).map: result =>
      assertEquals(result, CreateAlbumResult.Failed)
      assertEquals(refs.created.get(), None)

  private def singleTemplate(template: SpreadTemplate): SpreadTemplateService[IO] =
    buildSpreadTemplates(ListAlbumStartTemplatesResult.Listed(Vector(template)))

  private def buildSpreadTemplates(listResult: ListAlbumStartTemplatesResult): SpreadTemplateService[IO] =
    new SpreadTemplateService[IO]:
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult] = IO.pure(listResult)

  private def buildService(refs: Refs, fixture: RepositoryFixture): AlbumService[IO] =
    buildServiceAt(refs, fixture, createdAt)

  private def buildServiceAt(refs: Refs, fixture: RepositoryFixture, currentTime: Instant): AlbumService[IO] =
    buildServiceWith(refs, fixture, buildSpreadTemplates(ListAlbumStartTemplatesResult.Listed(seededAlbumStartTemplates)), currentTime)

  private def buildServiceWith(refs: Refs, fixture: RepositoryFixture, spreads: SpreadTemplateService[IO], currentTime: Instant): AlbumService[IO] =
    val identifiers = new IdGenerator[IO]:
      private val nextValue         = Ref.unsafe[IO, Int](10)
      override def nextId: IO[UUID] = nextValue.getAndUpdate(_ + 1).map(buildUuid)
    val clock = new Clock[IO]:
      override def now: IO[Instant] = IO.pure(currentTime)
    AlbumService.make(buildRepository(refs, fixture), spreads, identifiers, clock, buildPageSize)

  private def buildRepository(refs: Refs, fixture: RepositoryFixture): AlbumRepository[IO] = new AlbumRepository[IO]:
    override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
      IO.delay(refs.listCalls.updateAndGet(_ :+ (offset -> limit))).as(buildListResult(fixture, offset, limit))

    override def create(album: Album, start: Spread.AlbumStart, cover: Cover): IO[AlbumRepository.CreateResult] =
      IO.delay(refs.created.set((album, start, cover).some)).as(fixture.createResult)

    override def get(id: AlbumId): IO[AlbumRepository.GetResult] =
      IO.delay(refs.lastReadId.set(id.some)).as(fixture.getResult)

    override def rename(id: AlbumId, title: AlbumName): IO[AlbumRepository.RenameResult] =
      IO.delay(refs.renamed.set((id -> title).some)).as(fixture.renameResult)

    override def delete(id: AlbumId): IO[AlbumRepository.DeleteResult] =
      IO.pure(fixture.deleteResult)

  private def buildListResult(fixture: RepositoryFixture, offset: Int, limit: Int): AlbumRepository.ListResult =
    if fixture.listFailureOffset.contains(offset) then AlbumRepository.ListResult.Failed(AlbumRepositoryFailure.DatabaseFailure)
    else AlbumRepository.ListResult.Listed(AlbumPageSlice(fixture.albums.size.toLong, fixture.albums.slice(offset, offset + limit)))

  private def buildPageSize: AlbumPageSize = 6.refineUnsafe[Positive]

  private def buildFullSpreadBox: SlotBox =
    SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)

  private def buildAlbumStartTemplate(index: Int): SpreadTemplate =
    SpreadTemplate(
      buildTemplateId(index),
      s"Template $index".refineUnsafe[Not[Empty]],
      SpreadTemplateKind.AlbumStart,
      NonEmptySet.one(SpreadTemplateSlot.Text(buildFullSpreadBox))
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
    val listCalls: AtomicReference[Vector[(Int, Int)]]                      = AtomicReference(Vector.empty)
    val created: AtomicReference[Option[(Album, Spread.AlbumStart, Cover)]] = AtomicReference(None)
    val lastReadId: AtomicReference[Option[AlbumId]]                        = AtomicReference(None)
    val renamed: AtomicReference[Option[(AlbumId, AlbumName)]]              = AtomicReference(None)

  final private case class RepositoryFixture(
      albums: Vector[Album],
      listFailureOffset: Option[Int],
      createResult: AlbumRepository.CreateResult,
      getResult: AlbumRepository.GetResult,
      renameResult: AlbumRepository.RenameResult,
      deleteResult: AlbumRepository.DeleteResult
  )

  private object RepositoryFixture:
    val empty: RepositoryFixture = RepositoryFixture(
      albums = Vector.empty[Album],
      listFailureOffset = None,
      createResult = AlbumRepository.CreateResult.Created,
      getResult = AlbumRepository.GetResult.NotFound,
      renameResult = AlbumRepository.RenameResult.NotFound,
      deleteResult = AlbumRepository.DeleteResult.NotFound
    )
