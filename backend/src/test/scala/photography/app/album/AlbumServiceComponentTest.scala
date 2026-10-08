package photography.app.album

import cats.data.NonEmptySet
import cats.syntax.all.*
import cats.effect.{IO, Ref}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.domain.album.{AlbumComposition, AlbumId, AlbumName}
import photography.domain.layout.*

import java.time.Instant
import java.util.UUID

final class AlbumServiceComponentTest extends CatsEffectSuite:

  private val createdAt            = Instant.parse("2026-10-07T12:00:00Z")
  private val emptyRepositoryState = RepositoryState(
    albums = Vector.empty[AlbumSummary],
    template = Option.empty[SpreadTemplate],
    templateFailure = Option.empty[AlbumRepositoryFailure],
    createdComposition = Option.empty[AlbumComposition],
    createFailure = Option.empty[AlbumRepositoryFailure],
    readComposition = Option.empty[AlbumComposition],
    readFailure = Option.empty[AlbumRepositoryFailure],
    lastReadId = Option.empty[AlbumId],
    lastRenamed = Option.empty[(AlbumId, AlbumName)],
    renameResult = Option.empty[AlbumSummary],
    lastDeletedId = Option.empty[AlbumId],
    deleteResult = false,
    listFailureOffset = Option.empty[Int],
    listFailure = Option.empty[AlbumRepositoryFailure],
    renameFailure = Option.empty[AlbumRepositoryFailure],
    deleteFailure = Option.empty[AlbumRepositoryFailure],
    templateReads = 0,
    lastOffset = 0,
    lastLimit = 0
  )

  test("should return the last page and newest-first metadata for an out-of-range request"):
    val albums       = Vector.tabulate(13)(index => summary(12 - index))
    val initialState = emptyRepositoryState.copy(albums = albums)

    for
      repository <- FakeAlbumRepository.make(initialState)
      service = makeService(repository)
      result <- service.list(page = 99)
      state  <- repository.state.get
    yield
      val expectedPage =
        AlbumPage(Vector(summary(0)), 3, 6, 13, 3)
      assertEquals(result, expectedPage.asRight[AlbumServiceFailure])
      assertEquals(state.lastOffset, 12)
      assertEquals(state.lastLimit, 6)

  test("should return page one with empty results and zero pages"):
    for
      repository <- FakeAlbumRepository.make(emptyRepositoryState)
      result     <- makeService(repository).list(page = 1)
    yield assertEquals(result, AlbumPage(Vector.empty[AlbumSummary], 1, 6, 0, 0).asRight[AlbumServiceFailure])

  test("should report service failure when either album page query fails"):
    val albums         = Vector.tabulate(13)(index => summary(12 - index))
    val initialFailure = emptyRepositoryState.copy(
      listFailureOffset = 0.some,
      listFailure = AlbumRepositoryFailure.DatabaseFailure.some
    )
    val laterFailure = emptyRepositoryState.copy(
      albums = albums,
      listFailureOffset = 12.some,
      listFailure = AlbumRepositoryFailure.DatabaseFailure.some
    )

    for
      initialRepository <- FakeAlbumRepository.make(initialFailure)
      initialResult     <- makeService(initialRepository).list(page = 1)
      laterRepository   <- FakeAlbumRepository.make(laterFailure)
      laterResult       <- makeService(laterRepository).list(page = 99)
    yield
      assertEquals(initialResult, AlbumServiceFailure.ServiceFailure.asLeft[AlbumPage])
      assertEquals(laterResult, AlbumServiceFailure.ServiceFailure.asLeft[AlbumPage])

  test("should trim a title and create the complete initial composition"):
    val initialState = emptyRepositoryState.copy(template = albumStartTemplate.some)

    for
      repository <- FakeAlbumRepository.make(initialState)
      result     <- makeService(repository).create("  Northern Coast  ")
      state      <- repository.state.get
    yield
      val created = state.createdComposition.fold(fail("expected the created composition"))(identity)
      assertEquals(result.map(_.title), "Northern Coast".refineUnsafe[Not[Empty]].asRight[AlbumServiceFailure])
      assertEquals(created.album.createdAt, createdAt)
      assertEquals(created.start.slots.size, 1)
      assert(created.start.slots.headOption.exists:
        case SpreadSlot.Text(_, _, box) =>
          assertEquals(box, fullSpreadBox)
          true
        case _ => false)
      assertEquals(created.start.placements, Vector.empty[SpreadPlacement])
      assertEquals(created.cover.frontPlacement, Option.empty[CoverPlacement])
      assertEquals(created.cover.backPlacement, Option.empty[CoverPlacement])
      assertEquals(created.chapters, Vector.empty[photography.domain.album.ChapterComposition])

  test("should reject blank titles before reading a template or writing"):
    for
      repository <- FakeAlbumRepository.make(emptyRepositoryState)
      result     <- makeService(repository).create("   ")
      state      <- repository.state.get
    yield
      assertEquals(result, AlbumServiceFailure.InvalidTitle.asLeft[AlbumSummary])
      assertEquals(state.templateReads, 0)
      assertEquals(state.createdComposition, Option.empty[AlbumComposition])

  test("should return service failure when no built-in template exists"):
    for
      repository <- FakeAlbumRepository.make(emptyRepositoryState)
      result     <- makeService(repository).create("New album")
    yield assertEquals(result, AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])

  test("should reject AlbumStart templates with a photo slot or wrong template kind"):
    val photoTemplate = albumStartTemplate.copy(
      slots = NonEmptySet.one(SpreadTemplateSlot.Photo(fullSpreadBox))
    )
    val wrongKindTemplate = albumStartTemplate.copy(kind = SpreadTemplateKind.PhotosOnly)
    val nonFullBox        = fullSpreadBox.copy(
      x = 0.5.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    )
    val nonFullTemplate = albumStartTemplate.copy(
      slots = NonEmptySet.one(SpreadTemplateSlot.Text(nonFullBox))
    )

    for
      photoRepository     <- FakeAlbumRepository.make(emptyRepositoryState.copy(template = photoTemplate.some))
      photoResult         <- makeService(photoRepository).create("New album")
      photoState          <- photoRepository.state.get
      wrongKindRepository <- FakeAlbumRepository.make(emptyRepositoryState.copy(template = wrongKindTemplate.some))
      wrongKindResult     <- makeService(wrongKindRepository).create("New album")
      wrongKindState      <- wrongKindRepository.state.get
      nonFullRepository   <- FakeAlbumRepository.make(emptyRepositoryState.copy(template = nonFullTemplate.some))
      nonFullResult       <- makeService(nonFullRepository).create("New album")
      nonFullState        <- nonFullRepository.state.get
    yield
      assertEquals(photoResult, AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])
      assertEquals(photoState.createdComposition, Option.empty[AlbumComposition])
      assertEquals(wrongKindResult, AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])
      assertEquals(wrongKindState.createdComposition, Option.empty[AlbumComposition])
      assertEquals(nonFullResult, AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])
      assertEquals(nonFullState.createdComposition, Option.empty[AlbumComposition])

  test("should return service failure when the built-in template cannot be read"):
    val initialState = emptyRepositoryState.copy(templateFailure = AlbumRepositoryFailure.DatabaseFailure.some)

    for
      repository <- FakeAlbumRepository.make(initialState)
      result     <- makeService(repository).create("New album")
    yield assertEquals(result, AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])

  test("should return service failure when atomic creation fails"):
    val initialState = emptyRepositoryState.copy(
      template = albumStartTemplate.some,
      createFailure = AlbumRepositoryFailure.DatabaseFailure.some
    )

    for
      repository <- FakeAlbumRepository.make(initialState)
      result     <- makeService(repository).create("New album")
    yield assertEquals(result, AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])

  test("should return service failure when persisted composition is incomplete"):
    val initialState = emptyRepositoryState.copy(readFailure = AlbumRepositoryFailure.IncompleteComposition.some)

    for
      repository <- FakeAlbumRepository.make(initialState)
      result     <- makeService(repository).get(albumId(1))
    yield assertEquals(result, AlbumServiceFailure.ServiceFailure.asLeft[AlbumComposition])

  test("should return album not found when the requested album is missing"):
    for
      repository <- FakeAlbumRepository.make(emptyRepositoryState)
      result     <- makeService(repository).get(albumId(1))
      state      <- repository.state.get
    yield
      assertEquals(result, AlbumServiceFailure.AlbumNotFound.asLeft[AlbumComposition])
      assertEquals(state.lastReadId, albumId(1).some)

  test("should return a complete composition when the album exists"):
    val album = AlbumComposition(
      album = photography.domain.album.Album(
        albumId(2),
        "Album 2".refineUnsafe[Not[Empty]],
        "",
        createdAt
      ),
      start = SpreadComposition(
        SpreadId(uuid(3)),
        SpreadTemplateId(uuid(1)),
        Vector.empty[SpreadSlot],
        Vector.empty[SpreadPlacement]
      ),
      cover = CoverComposition(
        SpineOwner.Front,
        CoverSlot(CoverSlotId(uuid(4))),
        Option.empty[CoverPlacement],
        CoverSlot(CoverSlotId(uuid(5))),
        Option.empty[CoverPlacement]
      ),
      chapters = Vector.empty[photography.domain.album.ChapterComposition]
    )

    for
      repository <- FakeAlbumRepository.make(emptyRepositoryState.copy(readComposition = album.some))
      result     <- makeService(repository).get(albumId(2))
    yield assertEquals(result, album.asRight[AlbumServiceFailure])

  test("should reject a blank renamed title"):
    for
      repository <- FakeAlbumRepository.make(emptyRepositoryState)
      result     <- makeService(repository).rename(albumId(1), " ")
      state      <- repository.state.get
    yield
      assertEquals(result, AlbumServiceFailure.InvalidTitle.asLeft[AlbumSummary])
      assertEquals(state.lastRenamed, Option.empty[(AlbumId, AlbumName)])

  test("should return album not found when rename targets a missing album"):
    for
      repository <- FakeAlbumRepository.make(emptyRepositoryState)
      result     <- makeService(repository).rename(albumId(1), "Renamed")
    yield assertEquals(result, AlbumServiceFailure.AlbumNotFound.asLeft[AlbumSummary])

  test("should return the renamed album summary"):
    val renamed      = summary(1).copy(title = "Renamed".refineUnsafe[Not[Empty]])
    val initialState = emptyRepositoryState.copy(renameResult = renamed.some)

    for
      repository <- FakeAlbumRepository.make(initialState)
      result     <- makeService(repository).rename(albumId(1), " Renamed ")
      state      <- repository.state.get
    yield
      assertEquals(result, renamed.asRight[AlbumServiceFailure])
      assertEquals(state.lastRenamed, (albumId(1) -> "Renamed".refineUnsafe[Not[Empty]]).some)

  test("should report a service failure when rename persistence fails"):
    val initialState = emptyRepositoryState.copy(
      renameFailure = AlbumRepositoryFailure.DatabaseFailure.some
    )

    for
      repository <- FakeAlbumRepository.make(initialState)
      result     <- makeService(repository).rename(albumId(1), "Renamed")
    yield assertEquals(result, AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary])

  test("should report missing albums when deletion finds no row"):
    for
      repository <- FakeAlbumRepository.make(emptyRepositoryState)
      result     <- makeService(repository).delete(albumId(1))
    yield assertEquals(result, AlbumServiceFailure.AlbumNotFound.asLeft[Unit])

  test("should delete an existing album"):
    val initialState = emptyRepositoryState.copy(deleteResult = true)

    for
      repository <- FakeAlbumRepository.make(initialState)
      result     <- makeService(repository).delete(albumId(1))
    yield assertEquals(result, ().asRight[AlbumServiceFailure])

  test("should report a service failure when deletion persistence fails"):
    val initialState = emptyRepositoryState.copy(
      deleteFailure = AlbumRepositoryFailure.DatabaseFailure.some
    )

    for
      repository <- FakeAlbumRepository.make(initialState)
      result     <- makeService(repository).delete(albumId(1))
    yield assertEquals(result, AlbumServiceFailure.ServiceFailure.asLeft[Unit])

  test("should provide unique UUIDs and a current UTC instant from live ports"):
    for
      first  <- UUIDGenerator.make[IO].nextUUID
      second <- UUIDGenerator.make[IO].nextUUID
      now    <- UtcClock.make[IO].now
    yield
      assertNotEquals(first, second)
      assert(now.isAfter(Instant.parse("2020-01-01T00:00:00Z")))

  private def makeService(repository: FakeAlbumRepository): AlbumService[IO] =
    val identifiers = new UUIDGenerator[IO]:
      private val nextValue           = Ref.unsafe[IO, Int](10)
      override def nextUUID: IO[UUID] =
        nextValue.getAndUpdate(_ + 1).map(uuid)
    val clock = new UtcClock[IO]:
      override def now: IO[Instant] = IO.pure(createdAt)
    AlbumService.make(repository, identifiers, clock)

  private def albumStartTemplate: SpreadTemplate =
    SpreadTemplate(
      id = SpreadTemplateId(uuid(1)),
      name = "Built-in AlbumStart".refineUnsafe[Not[Empty]],
      kind = SpreadTemplateKind.AlbumStart,
      slots = NonEmptySet.one(SpreadTemplateSlot.Text(fullSpreadBox))
    )

  private def fullSpreadBox: SlotBox =
    val x      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val y      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val width  = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    val height = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    SlotBox(x, y, width, height)

  private def summary(index: Int): AlbumSummary =
    AlbumSummary(
      id = albumId(index),
      title = s"Album $index".refineUnsafe[Not[Empty]],
      createdAt = createdAt.plusSeconds(index.toLong)
    )

  private def albumId(index: Int): AlbumId =
    AlbumId(uuid(index))

  private def uuid(index: Int): UUID =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")

  final private case class RepositoryState(
      albums: Vector[AlbumSummary],
      template: Option[SpreadTemplate],
      templateFailure: Option[AlbumRepositoryFailure],
      createdComposition: Option[AlbumComposition],
      createFailure: Option[AlbumRepositoryFailure],
      readComposition: Option[AlbumComposition],
      readFailure: Option[AlbumRepositoryFailure],
      lastReadId: Option[AlbumId],
      lastRenamed: Option[(AlbumId, AlbumName)],
      renameResult: Option[AlbumSummary],
      lastDeletedId: Option[AlbumId],
      deleteResult: Boolean,
      listFailureOffset: Option[Int],
      listFailure: Option[AlbumRepositoryFailure],
      renameFailure: Option[AlbumRepositoryFailure],
      deleteFailure: Option[AlbumRepositoryFailure],
      templateReads: Int,
      lastOffset: Int,
      lastLimit: Int
  )

  final private class FakeAlbumRepository(val state: Ref[IO, RepositoryState])
      extends AlbumRepository[IO]:

    override def list(offset: Int, limit: Int): IO[Either[AlbumRepositoryFailure, AlbumPageSlice]] =
      state.update(_.copy(lastOffset = offset, lastLimit = limit)) *>
        state.get.map: current =>
          if current.listFailureOffset.contains(offset) then
            current.listFailure
              .fold(AlbumRepositoryFailure.DatabaseFailure)(identity)
              .asLeft[AlbumPageSlice]
          else
            AlbumPageSlice(current.albums.size.toLong, current.albums.slice(offset, offset + limit))
              .asRight[AlbumRepositoryFailure]

    override def builtInAlbumStartTemplate
        : IO[Either[AlbumRepositoryFailure, Option[SpreadTemplate]]] =
      state.update(current => current.copy(templateReads = current.templateReads + 1)) *>
        state.get.map: current =>
          current.templateFailure match
            case Some(failure) => failure.asLeft[Option[SpreadTemplate]]
            case None          => current.template.asRight[AlbumRepositoryFailure]

    override def create(composition: AlbumComposition)
        : IO[Either[AlbumRepositoryFailure, Unit]] =
      state.update(_.copy(createdComposition = composition.some)).flatMap: _ =>
        state.get.map: current =>
          current.createFailure match
            case Some(failure) => failure.asLeft[Unit]
            case None          => ().asRight[AlbumRepositoryFailure]

    override def getComposition(id: AlbumId)
        : IO[Either[AlbumRepositoryFailure, Option[AlbumComposition]]] =
      state.update(_.copy(lastReadId = id.some)) *>
        state.get.map: current =>
          current.readFailure match
            case Some(failure) => failure.asLeft[Option[AlbumComposition]]
            case None          => current.readComposition.asRight[AlbumRepositoryFailure]

    override def rename(id: AlbumId, title: AlbumName)
        : IO[Either[AlbumRepositoryFailure, Option[AlbumSummary]]] =
      state.update(_.copy(lastRenamed = (id -> title).some)) *>
        state.get.map: current =>
          current.renameFailure match
            case Some(failure) => failure.asLeft[Option[AlbumSummary]]
            case None          => current.renameResult.asRight[AlbumRepositoryFailure]

    override def delete(id: AlbumId): IO[Either[AlbumRepositoryFailure, Boolean]] =
      state.update(_.copy(lastDeletedId = id.some)) *>
        state.get.map: current =>
          current.deleteFailure match
            case Some(failure) => failure.asLeft[Boolean]
            case None          => current.deleteResult.asRight[AlbumRepositoryFailure]

  private object FakeAlbumRepository:
    def make(initialState: RepositoryState): IO[FakeAlbumRepository] =
      Ref.of[IO, RepositoryState](initialState).map(new FakeAlbumRepository(_))
