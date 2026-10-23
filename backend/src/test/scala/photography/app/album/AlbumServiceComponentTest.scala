package photography.app.album

import cats.data.NonEmptySet
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.app.spread.{SpreadRepository, SpreadRepositoryFailure}
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*

import java.time.Instant
import java.util.UUID

final class AlbumServiceComponentTest extends CatsEffectSuite:

  private val createdAt = Instant.parse("2026-10-07T12:00:00Z")

  test("should return the last Album page in newest-first order for an out-of-range request"):
    val albums     = Vector.tabulate(13)(index => buildAlbum(12 - index))
    val expected   = AlbumPage(Vector(buildAlbum(0)), 3, 6, 13, 3)
    val repository = FakeAlbumRepository.make(RepositoryFixture.empty.copy(albums = albums))

    for
      fake   <- repository
      result <- buildService(fake).list(page = 99)
      state  <- fake.state.get
    yield
      assertEquals(result, ListAlbumsResult.Listed(expected))
      assertEquals(state.listCalls, Vector(0 -> 6, 12 -> 6))

  test("should return page one with empty results and zero pages"):
    val repository = FakeAlbumRepository.make(RepositoryFixture.empty)
    val expected   = AlbumPage(Vector.empty[Album], 1, 6, 0, 0)

    for result <- repository.flatMap(buildService(_).list(page = 1))
    yield assertEquals(result, ListAlbumsResult.Listed(expected))

  test("should not repeat the first query when the first page is selected"):
    val repository = FakeAlbumRepository.make(RepositoryFixture.empty.copy(albums = Vector.tabulate(13)(buildAlbum)))

    for
      fake  <- repository
      _     <- buildService(fake).list(page = 1)
      state <- fake.state.get
    yield assertEquals(state.listCalls, Vector(0 -> 6))

  test("should report failure when the first album page query fails"):
    val repository = FakeAlbumRepository.make(RepositoryFixture.empty.copy(listFailureOffset = 0.some))

    for result <- repository.flatMap(buildService(_).list(page = 1))
    yield assertEquals(result, ListAlbumsResult.Failed)

  test("should report failure when the selected album page query fails"):
    val fixture = RepositoryFixture.empty.copy(albums = Vector.tabulate(13)(buildAlbum), listFailureOffset = 12.some)

    for result <- FakeAlbumRepository.make(fixture).flatMap(buildService(_).list(page = 99))
    yield assertEquals(result, ListAlbumsResult.Failed)

  test("should trim a title and create an Album, album-start Spread, and empty Cover"):
    for
      repository <- FakeAlbumRepository.make(RepositoryFixture.empty)
      result     <- buildService(repository).create("  Northern Coast  ")
      state      <- repository.state.get
    yield
      val (album, start, cover) = state.created.getOrElse(fail("expected a created composition"))
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

    for result <- FakeAlbumRepository.make(RepositoryFixture.empty).flatMap(buildServiceAt(_, suppliedTime).create("Album"))
    yield result match
      case CreateAlbumResult.Created(album) => assertEquals(album.createdAt, expectedTime)
      case other                            => fail(s"unexpected result: $other")

  test("should reject blank titles before writing"):
    for
      repository <- FakeAlbumRepository.make(RepositoryFixture.empty)
      result     <- buildService(repository).create("   ")
      state      <- repository.state.get
    yield
      assertEquals(result, CreateAlbumResult.InvalidTitle)
      assertEquals(state.created, None)

  test("should report failure when atomic creation fails"):
    val fixture = RepositoryFixture.empty.copy(createResult = AlbumRepository.CreateResult.Failed(AlbumRepositoryFailure.DatabaseFailure))

    for result <- FakeAlbumRepository.make(fixture).flatMap(buildService(_).create("New album"))
    yield assertEquals(result, CreateAlbumResult.Failed)

  test("should report failure when album lookup fails"):
    val fixture = RepositoryFixture.empty.copy(getResult = AlbumRepository.GetResult.Failed(AlbumRepositoryFailure.InvalidAlbumData))

    for result <- FakeAlbumRepository.make(fixture).flatMap(buildService(_).get(buildAlbumId(1)))
    yield assertEquals(result, GetAlbumResult.Failed)

  test("should report not found when the requested album is missing"):
    for
      repository <- FakeAlbumRepository.make(RepositoryFixture.empty.copy(getResult = AlbumRepository.GetResult.NotFound))
      result     <- buildService(repository).get(buildAlbumId(1))
      state      <- repository.state.get
    yield
      assertEquals(result, GetAlbumResult.NotFound)
      assertEquals(state.lastReadId, buildAlbumId(1).some)

  test("should return the Album when it exists"):
    val expected = buildAlbum(2)
    val fixture  = RepositoryFixture.empty.copy(getResult = AlbumRepository.GetResult.Found(expected))

    for result <- FakeAlbumRepository.make(fixture).flatMap(buildService(_).get(buildAlbumId(2)))
    yield assertEquals(result, GetAlbumResult.Found(expected))

  test("should reject a blank renamed title without writing"):
    for
      repository <- FakeAlbumRepository.make(RepositoryFixture.empty)
      result     <- buildService(repository).rename(buildAlbumId(1), " ")
      state      <- repository.state.get
    yield
      assertEquals(result, RenameAlbumResult.InvalidTitle)
      assertEquals(state.renamed, None)

  test("should report not found when rename targets a missing album"):
    for result <- FakeAlbumRepository.make(RepositoryFixture.empty).flatMap(buildService(_).rename(buildAlbumId(1), "Renamed"))
    yield assertEquals(result, RenameAlbumResult.NotFound)

  test("should return the renamed Album with a trimmed title"):
    val renamed = buildAlbum(1).copy(title = "Renamed".refineUnsafe[Not[Empty]])
    val fixture = RepositoryFixture.empty.copy(renameResult = AlbumRepository.RenameResult.Renamed(renamed))

    for
      repository <- FakeAlbumRepository.make(fixture)
      result     <- buildService(repository).rename(buildAlbumId(1), " Renamed ")
      state      <- repository.state.get
    yield
      assertEquals(result, RenameAlbumResult.Renamed(renamed))
      assertEquals(state.renamed, (buildAlbumId(1) -> "Renamed".refineUnsafe[Not[Empty]]).some)

  test("should report failure when rename persistence fails"):
    val fixture = RepositoryFixture.empty.copy(renameResult = AlbumRepository.RenameResult.Failed(AlbumRepositoryFailure.DatabaseFailure))

    for result <- FakeAlbumRepository.make(fixture).flatMap(buildService(_).rename(buildAlbumId(1), "Renamed"))
    yield assertEquals(result, RenameAlbumResult.Failed)

  test("should report not found when deletion finds no row"):
    for result <- FakeAlbumRepository.make(RepositoryFixture.empty).flatMap(buildService(_).delete(buildAlbumId(1)))
    yield assertEquals(result, DeleteAlbumResult.NotFound)

  test("should delete an existing album"):
    val fixture = RepositoryFixture.empty.copy(deleteResult = AlbumRepository.DeleteResult.Deleted)

    for result <- FakeAlbumRepository.make(fixture).flatMap(buildService(_).delete(buildAlbumId(1)))
    yield assertEquals(result, DeleteAlbumResult.Deleted)

  test("should report failure when deletion persistence fails"):
    val fixture = RepositoryFixture.empty.copy(deleteResult = AlbumRepository.DeleteResult.Failed(AlbumRepositoryFailure.DatabaseFailure))

    for result <- FakeAlbumRepository.make(fixture).flatMap(buildService(_).delete(buildAlbumId(1)))
    yield assertEquals(result, DeleteAlbumResult.Failed)

  test("should use the first AlbumStart template in database order when creating"):
    val spreads =
      SpreadTemplateCatalog(SpreadRepository.ListAlbumStartTemplatesResult.Listed(Vector(buildAlbumStartTemplate(21), buildAlbumStartTemplate(20))))

    for
      repository <- FakeAlbumRepository.make(RepositoryFixture.empty)
      _          <- buildServiceWith(repository, spreads, createdAt).create("Northern Coast")
      state      <- repository.state.get
    yield assertEquals(state.created.map(_._2.template), buildTemplateId(21).some)

  test("should fail creation without writing when no AlbumStart template is seeded"):
    val spreads = SpreadTemplateCatalog(SpreadRepository.ListAlbumStartTemplatesResult.Listed(Vector.empty[SpreadTemplate]))

    for
      repository <- FakeAlbumRepository.make(RepositoryFixture.empty)
      result     <- buildServiceWith(repository, spreads, createdAt).create("Northern Coast")
      state      <- repository.state.get
    yield
      assertEquals(result, CreateAlbumResult.Failed)
      assertEquals(state.created, None)

  test("should fail creation without writing when the template lookup fails"):
    val spreads = SpreadTemplateCatalog(SpreadRepository.ListAlbumStartTemplatesResult.Failed(SpreadRepositoryFailure.DatabaseFailure))

    for
      repository <- FakeAlbumRepository.make(RepositoryFixture.empty)
      result     <- buildServiceWith(repository, spreads, createdAt).create("Northern Coast")
      state      <- repository.state.get
    yield
      assertEquals(result, CreateAlbumResult.Failed)
      assertEquals(state.created, None)

  test("should provide unique UUIDs and a current UTC instant from live ports"):
    for
      first  <- UUIDGenerator.make[IO].nextUUID
      second <- UUIDGenerator.make[IO].nextUUID
      now    <- UtcClock.make[IO].now
    yield
      assertNotEquals(first, second)
      assert(now.isAfter(Instant.parse("2020-01-01T00:00:00Z")))

  private def buildService(repository: FakeAlbumRepository): AlbumService[IO] =
    buildServiceAt(repository, createdAt)

  private def buildServiceAt(repository: FakeAlbumRepository, currentTime: Instant): AlbumService[IO] =
    buildServiceWith(repository, SpreadTemplateCatalog(SpreadRepository.ListAlbumStartTemplatesResult.Listed(seededAlbumStartTemplates)), currentTime)

  private def buildServiceWith(
      repository: FakeAlbumRepository,
      spreads: SpreadTemplateCatalog,
      currentTime: Instant
  ): AlbumService[IO] =
    val identifiers = new UUIDGenerator[IO]:
      private val nextValue           = Ref.unsafe[IO, Int](10)
      override def nextUUID: IO[UUID] = nextValue.getAndUpdate(_ + 1).map(buildUuid)
    val clock = new UtcClock[IO]:
      override def now: IO[Instant] = IO.pure(currentTime)
    AlbumService.make(repository, spreads, identifiers, clock, buildPageSize)

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

  final private case class RepositoryState(
      listCalls: Vector[(Int, Int)],
      created: Option[(Album, Spread.AlbumStart, Cover)],
      lastReadId: Option[AlbumId],
      renamed: Option[(AlbumId, AlbumName)]
  )

  final private case class SpreadTemplateCatalog(listResult: SpreadRepository.ListAlbumStartTemplatesResult) extends SpreadRepository[IO]:
    override def listAlbumStartTemplates: IO[SpreadRepository.ListAlbumStartTemplatesResult] = IO.pure(listResult)

  final private class FakeAlbumRepository(
      fixture: RepositoryFixture,
      val state: Ref[IO, RepositoryState]
  ) extends AlbumRepository[IO]:

    override def list(offset: Int, limit: Int): IO[AlbumRepository.ListResult] =
      state.update(current => current.copy(listCalls = current.listCalls :+ (offset -> limit))).as(buildListResult(offset, limit))

    override def create(album: Album, start: Spread.AlbumStart, cover: Cover): IO[AlbumRepository.CreateResult] =
      state.update(_.copy(created = (album, start, cover).some)).as(fixture.createResult)

    override def getAlbum(id: AlbumId): IO[AlbumRepository.GetResult] =
      state.update(_.copy(lastReadId = id.some)).as(fixture.getResult)

    override def rename(id: AlbumId, title: AlbumName): IO[AlbumRepository.RenameResult] =
      state.update(_.copy(renamed = (id -> title).some)).as(fixture.renameResult)

    override def delete(id: AlbumId): IO[AlbumRepository.DeleteResult] =
      IO.pure(fixture.deleteResult)

    private def buildListResult(offset: Int, limit: Int): AlbumRepository.ListResult =
      if fixture.listFailureOffset.contains(offset) then
        AlbumRepository.ListResult.Failed(AlbumRepositoryFailure.DatabaseFailure)
      else
        val slice = AlbumPageSlice(fixture.albums.size.toLong, fixture.albums.slice(offset, offset + limit))
        AlbumRepository.ListResult.Listed(slice)

  private object FakeAlbumRepository:
    def make(fixture: RepositoryFixture): IO[FakeAlbumRepository] =
      Ref.of[IO, RepositoryState](RepositoryState(Vector.empty[(Int, Int)], None, None, None)).map(new FakeAlbumRepository(fixture, _))
