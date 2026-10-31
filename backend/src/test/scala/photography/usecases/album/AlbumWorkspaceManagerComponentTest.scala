package photography.usecases.album
import photography.domain.gear.CameraSelection

import photography.usecases.photo.ChapterPhotoImport
import photography.usecases.photo.PhotoInventory
import photography.usecases.photo.PhotoManager
import photography.usecases.photo.PhotoWriteRecovery

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.album.CountryRepository
import photography.usecases.album.AlbumManager.*
import photography.usecases.album.AlbumWorkspaceManager.GetResult
import photography.usecases.album.AlbumWorkspaceManager.{ChapterPhotos, ChapterResult}
import photography.usecases.photo.ChapterPhotoImport.{ExecutionOutcome, Request, Scheduling, Status}

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class AlbumWorkspaceManagerComponentTest extends CatsEffectSuite:
  private val album =
    val box     = SlotBox(x = 0.0, y = 0.0, width = 100.0, height = 100.0)
    val text    = SpreadSlot.Text(TextSpreadSlotId(new UUID(0L, 3L)), box)
    val opening = Spread.AlbumStart(
      SpreadId(new UUID(0L, 2L)),
      SpreadTemplateId(new UUID(0L, 4L)),
      photoSlots = Vector.empty[SpreadSlot.Photo],
      text,
      placements = Vector.empty[SpreadPlacement]
    )
    Album(
      AlbumId(new UUID(0L, 1L)),
      year = 2026,
      title = "Our year",
      synopsis = "",
      authors = Author.all,
      Instant.EPOCH,
      opening,
      chapters = Vector.empty[Chapter]
    )
  private val countries = NonEmptyList.one(
    Country(CountryId(new UUID(0L, 5L)), code = "RO", name = "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
  )
  private val chapter =
    val id              = ChapterId(new UUID(0L, 6L))
    val openingId       = SpreadId(new UUID(0L, 7L))
    val template        = album.opening.template
    val textSlot        = album.opening.textSlot
    val emptyPhotos     = Vector.empty[SpreadSlot.Photo]
    val emptyPlacements = Vector.empty[SpreadPlacement]
    val emptySpreads    = Vector.empty[Spread.PhotosOnly]
    val opening         = Spread.ChapterStart(openingId, template, photoSlots = emptyPhotos, textSlot, placements = emptyPlacements)
    val destination     = Destination(DestinationId(new UUID(0L, 8L)), name = "Coast", countries)
    val period          = VisitPeriod(LocalDate.of(2026, 1, 1), days = 1)
    Chapter(
      id,
      destination,
      period,
      description = "",
      gear = Vector.empty[CameraSelection],
      sourceFolder = "/photos/coast",
      opening,
      spreads = emptySpreads
    )
  private val emptyInventory = PhotoInventory.Inventory(photos = Vector.empty[Photo], placed = Set.empty)
  private val idle           = ExecutionOutcome(Status.Idle, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue])

  test("should assemble the complete album and selectable countries with the configured photo root"):
    val manager  = buildManager(GetAlbumResult.Found(album), CountryRepository.ListResult.Listed(countries))
    val expected =
      GetResult.Found(album, countries = countries, photoRoot = "/photos", photoInventories = Map.empty[ChapterId, ChapterPhotos])

    manager.get(album.id).map(result => assertEquals(result, expected))

  test("should load each chapter inventory once and preserve explicit inventory failures"):
    val other         = chapter.copy(id = ChapterId(new UUID(0L, 9L)), sourceFolder = "/photos/other")
    val current       = album.copy(chapters = Vector(chapter, other))
    val running       = ExecutionOutcome(Status.Running, retirementIssues = Vector.empty[PhotoWriteRecovery.Issue])
    val cleanupFailed = ExecutionOutcome(
      Status.Finished(PhotoManager.RefreshResult(unsupportedCount = 2, issues = Vector.empty[PhotoManager.Issue])),
      retirementIssues = Vector(PhotoWriteRecovery.Issue.References(PhotoWriteIntentId(new UUID(0L, 10L))))
    )
    val outcomes    = Map(chapter.id -> running, other.id -> cleanupFailed)
    val inventories = Map(chapter.id -> ChapterPhotos(emptyInventory, running), other.id -> ChapterPhotos(emptyInventory, cleanupFailed))
    val execution   = (id: ChapterId) => IO.fromOption(outcomes.get(id))(new AssertionError("Unexpected chapter execution read"))
    val expected    = GetResult.Found(current, countries = countries, photoRoot = "/photos", photoInventories = inventories)
    val savedAlbum  = GetAlbumResult.Found(current)
    val catalog     = CountryRepository.ListResult.Listed(countries)
    val failedRead  = buildManager(savedAlbum, catalog, inventory = _ => PhotoInventory.ListResult.Failed.pure[IO]).get(album.id)

    for
      reads <- Ref.of[IO, Vector[ChapterId]](Vector.empty)
      photos = (id: ChapterId) => reads.update(_ :+ id).as(PhotoInventory.ListResult.Listed(emptyInventory))
      result <- buildManager(savedAlbum, catalog, inventory = photos, execution = execution).get(album.id)
      actual <- reads.get
      failed <- failedRead
    yield
      assertEquals(result, expected)
      assertEquals(actual, Vector(chapter.id, other.id))
      assertEquals(failed, GetResult.Failed)

  test("should preserve missing albums and propagate album or catalog failures"):
    val scenarios = Vector(
      (GetAlbumResult.NotFound, CountryRepository.ListResult.Failed, GetResult.AlbumNotFound),
      (GetAlbumResult.Failed, CountryRepository.ListResult.Listed(countries), GetResult.Failed),
      (GetAlbumResult.Found(album), CountryRepository.ListResult.Failed, GetResult.Failed)
    )

    scenarios.traverse_ { case (albumResult, countryResult, expected) =>
      val manager = buildManager(albumResult, countryResult)

      manager.get(album.id).map(result => assertEquals(result, expected))
    }

  test("should expose chapter inventory with current execution or explicit missing and failure outcomes"):
    val running   = ExecutionOutcome(Status.Running, retirementIssues = Vector(PhotoWriteRecovery.Issue.Listing))
    val snapshot  = ChapterPhotos(emptyInventory, running)
    val execution = (id: ChapterId) => IO(assertEquals(id, chapter.id)).as(running)

    Vector(
      (PhotoInventory.ListResult.Listed(emptyInventory), ChapterResult.Found(snapshot)),
      (PhotoInventory.ListResult.ChapterNotFound, ChapterResult.ChapterNotFound),
      (PhotoInventory.ListResult.Failed, ChapterResult.Failed)
    ).traverse_ { case (inventory, expected) =>
      val manager = buildManager(
        GetAlbumResult.Found(album),
        CountryRepository.ListResult.Listed(countries),
        inventory = _ => inventory.pure[IO],
        execution = execution
      )

      manager.listChapter(chapter.id).map(actual => assertEquals(actual, expected))
    }

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      albumResult: GetAlbumResult,
      countryResult: CountryRepository.ListResult,
      inventory: ChapterId => IO[PhotoInventory.ListResult] =
        _ => IO.raiseError[PhotoInventory.ListResult](new AssertionError("Unexpected inventory read")),
      execution: ChapterId => IO[ExecutionOutcome] = _ => idle.pure[IO]
  ) =
    val albums = new AlbumManager[IO]:
      override def saveSynopsis(id: AlbumId, text: String): IO[AlbumManager.SaveSynopsisResult] =
        IO.raiseError[AlbumManager.SaveSynopsisResult](new AssertionError("Unexpected synopsis mutation"))
      override def saveAuthors(id: AlbumId, authors: AlbumAuthors): IO[AlbumManager.SaveAuthorsResult] =
        IO.raiseError[AlbumManager.SaveAuthorsResult](new AssertionError("Unexpected author mutation"))
      override def get(id: AlbumId): IO[GetAlbumResult]                              = albumResult.pure[IO]
      override def list(page: Int): IO[ListAlbumsResult]                             = ListAlbumsResult.Failed.pure[IO]
      override def create(year: AlbumYear, title: AlbumTitle): IO[CreateAlbumResult] = CreateAlbumResult.Failed.pure[IO]
      override def rename(id: AlbumId, title: AlbumTitle): IO[RenameResult]          = RenameResult.Failed.pure[IO]
      override def delete(id: AlbumId): IO[DeleteAlbumResult]                        = DeleteAlbumResult.Failed.pure[IO]
    val catalog = new CountryRepository[IO]:
      override def list: IO[CountryRepository.ListResult] = countryResult.pure[IO]
    val photos = new PhotoInventory[IO]:
      override def listChapter(id: ChapterId): IO[PhotoInventory.ListResult] = inventory(id)
    val imports = new ChapterPhotoImport[IO]:
      override def status(id: ChapterId): IO[ExecutionOutcome]                                    = execution(id)
      override def prepare(parent: AlbumId, chapter: ChapterId, source: PhotoSource): IO[Request] =
        IO.raiseError[Request](new AssertionError("Unexpected import preparation"))
      override def launch(request: Request): IO[Scheduling] = IO.raiseError[Scheduling](new AssertionError("Unexpected import launch"))
      override def retire(parent: AlbumId, retirements: Vector[PhotoWriteIntent.Retirement]): IO[Unit] =
        IO.raiseError[Unit](new AssertionError("Unexpected retirement"))
      override def suspend(chapter: ChapterId): IO[Unit] = IO.raiseError[Unit](new AssertionError("Unexpected suspension"))
    AlbumWorkspaceManager.make(albums, catalog, photos, imports, photoRoot = "/photos")
  // scalafix:on DisableSyntax.defaultArgs
