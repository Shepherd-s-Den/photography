package photography.app.runtime

import cats.data.{NonEmptyList, NonEmptyVector}
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.Transactor
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.adapters.sqlite.photo.SqlitePhotoRepository
import photography.app.config.SqliteConfig
import photography.capabilities.{Clock, IdGenerator}
import photography.domain.album.*
import photography.domain.gear.*
import photography.ports.photo.ThumbnailStorage
import photography.usecases.album.{AlbumManager, AlbumWorkspaceManager}
import photography.usecases.chapter.{ChapterManager, ChapterMetadataManager}
import photography.usecases.gear.GearManager
import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.UUID

final class AppManagersIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given Clock[IO] with
    override def readInstant: IO[Instant] = Instant.EPOCH.pure[IO]
  private given IdGenerator[IO] = IdGenerator.make[IO]

  test("should compose real catalog and chapter owners preserving metadata aggregate and independent unused lifecycles"):
    buildDirectory.use: root =>
      buildDatabase(root).use: sqlite =>
        val source: PhotoSource = root.resolve("source").toString.refineUnsafe

        buildManagers(sqlite, source).use: managers =>
          val arrange =
            for
              album       <- createAlbum(managers)
              countries   <- managers.workspaces.get(album.id)
              chapter     <- createChapter(managers, album.id, countries, source)
              description <- managers.chapters.saveDescription(album.id, chapter, text = "Retained\ntext")
              camera      <- createCamera(managers, code = "body")
              second      <- createCamera(managers, code = "second")
              lens        <- createLens(managers, code = "lens")
              unused      <- createCamera(managers, code = "unused")
              independent <- createLens(managers, code = "independent")
              before      <- managers.albums.get(album.id)
            yield (album, chapter, description, camera, second, lens, unused, independent, before)

          arrange.flatMap { case (album, chapter, description, camera, second, lens, unused, independent, before) =>
            val sharedUse     = CameraUse.Interchangeable(lenses = NonEmptyVector.one(lens.id))
            val selection     = Vector(CameraSelection(camera.id, sharedUse), CameraSelection(second.id, sharedUse))
            val editedCamera  = camera.copy(friendlyName = "Renamed")
            val editedLens    = lens.copy(friendlyName = "Renamed lens")
            val expectedAlbum = before match
              case AlbumManager.GetAlbumResult.Found(value) =>
                AlbumManager.GetAlbumResult.Found(value.copy(chapters = value.chapters.map(_.copy(gear = selection))))
              case value => fail(message = s"Expected album: $value")
            val expectedCatalog = GearCatalog(
              cameras = Vector(editedCamera, second),
              lenses = Vector(editedLens, independent)
            )

            for
              saved              <- managers.chapterGear.replaceGear(album.id, chapter, selection = selection)
              read               <- managers.chapterGear.readGear(album.id, chapter)
              protectedCamera    <- managers.gear.updateCamera(camera.copy(kind = CameraKind.FixedLens))
              protectedLens      <- managers.gear.updateLens(lens.copy(mount = LensMount.NikonZ))
              usedCamera         <- managers.gear.deleteCamera(camera.id)
              usedLens           <- managers.gear.deleteLens(lens.id)
              cameraUpdated      <- managers.gear.updateCamera(editedCamera)
              lensUpdated        <- managers.gear.updateLens(editedLens)
              editedUnused       <- managers.gear.updateCamera(unused.copy(kind = CameraKind.FixedLens))
              deletedUnused      <- managers.gear.deleteCamera(unused.id)
              after              <- managers.albums.get(album.id)
              catalog            <- managers.gear.readCatalog
              deletedIndependent <- managers.gear.deleteLens(independent.id)
              cleared            <- managers.chapterGear.replaceGear(album.id, chapter, selection = Vector.empty[CameraSelection])
              deletedCamera      <- managers.gear.deleteCamera(camera.id)
              deletedLens        <- managers.gear.deleteLens(lens.id)
              restored           <- managers.albums.get(album.id)
            yield
              assertEquals(description, ChapterMetadataManager.SaveDescriptionResult.Saved)
              assertEquals(saved, ChapterManager.GearReplaceResult.Saved)
              assertEquals(read, ChapterManager.GearReadResult.Found(selection = selection))
              assertEquals(protectedCamera, GearManager.CameraUpdateResult.InvalidatesSelection)
              assertEquals(protectedLens, GearManager.LensUpdateResult.InvalidatesSelection)
              assertEquals(usedCamera, GearManager.CameraDeleteResult.Used)
              assertEquals(usedLens, GearManager.LensDeleteResult.Used)
              assertEquals(cameraUpdated, GearManager.CameraUpdateResult.Updated)
              assertEquals(lensUpdated, GearManager.LensUpdateResult.Updated)
              assertEquals(editedUnused, GearManager.CameraUpdateResult.Updated)
              assertEquals(deletedUnused, GearManager.CameraDeleteResult.Deleted)
              assertEquals(after, expectedAlbum)
              assertEquals(catalog, GearManager.CatalogResult.Found(expectedCatalog))
              assertEquals(deletedIndependent, GearManager.LensDeleteResult.Deleted)
              assertEquals(cleared, ChapterManager.GearReplaceResult.Saved)
              assertEquals(deletedCamera, GearManager.CameraDeleteResult.Deleted)
              assertEquals(deletedLens, GearManager.LensDeleteResult.Deleted)
              assertEquals(restored, before)
          }

  test("should serialize runtime replacement against incompatible catalog edits under the existing shared coordination"):
    buildDirectory.use: root =>
      buildDatabase(root).use: sqlite =>
        val source: PhotoSource = root.resolve("source").toString.refineUnsafe

        buildManagers(sqlite, source).use: managers =>
          val arrange =
            for
              album     <- createAlbum(managers)
              countries <- managers.workspaces.get(album.id)
              chapter   <- createChapter(managers, album.id, countries, source)
              camera    <- createCamera(managers, code = "body")
              lens      <- createLens(managers, code = "lens")
            yield (album.id, chapter, camera, lens)

          arrange.flatMap { case (album, chapter, camera, lens) =>
            val selection        = Vector(CameraSelection(camera.id, CameraUse.Interchangeable(lenses = NonEmptyVector.one(lens.id))))
            val replaceSelection = managers.chapterGear.replaceGear(album, chapter, selection = selection)
            val changeMount      = managers.gear.updateLens(lens.copy(mount = LensMount.NikonZ))
            val race             = (replaceSelection, changeMount).parTupled

            Vector.range(start = 0, end = 24).traverse_ { _ =>
              for
                _       <- managers.chapterGear.replaceGear(album, chapter, selection = Vector.empty[CameraSelection])
                _       <- managers.gear.updateLens(lens)
                results <- race
                read    <- managers.chapterGear.readGear(album, chapter)
                catalog <- managers.gear.readCatalog
              yield
                val actual         = (results, read, catalog)
                val selectionFirst = (
                  (ChapterManager.GearReplaceResult.Saved, GearManager.LensUpdateResult.InvalidatesSelection),
                  ChapterManager.GearReadResult.Found(selection = selection),
                  GearManager.CatalogResult.Found(GearCatalog(cameras = Vector(camera), lenses = Vector(lens)))
                )
                val editFirst = (
                  (ChapterManager.GearReplaceResult.Rejected(GearManager.Failure.IncompatibleLens), GearManager.LensUpdateResult.Updated),
                  ChapterManager.GearReadResult.Found(selection = Vector.empty[CameraSelection]),
                  GearManager.CatalogResult.Found(GearCatalog(cameras = Vector(camera), lenses = Vector(lens.copy(mount = LensMount.NikonZ))))
                )

                assert(cond = Set(selectionFirst, editFirst).contains(actual))
            }
          }

  private def buildManagers(sqlite: Transactor[IO], source: PhotoSource) =
    val thumbnails = new ThumbnailStorage[IO]:
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        IO.raiseError[ThumbnailStorage.StoreResult](new AssertionError("Runtime gear must not write thumbnails"))
      override def readThumbnail(location: ThumbnailLocation, version: String :| Not[Blank]): IO[ThumbnailStorage.ReadResult] =
        IO.raiseError[ThumbnailStorage.ReadResult](new AssertionError("Runtime gear must not read thumbnails"))
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        IO.raiseError[ThumbnailStorage.RemoveResult](new AssertionError("Runtime gear must not remove thumbnails"))
    val photos = SqlitePhotoRepository.make
    AppManagers.make[IO](sqlite, pageSize = 6, source, photos, thumbnails, ThumbnailQuality.Low)

  private def createAlbum(managers: AppManagers[IO]) =
    managers.albums.create(year = 2026, title = "Runtime album").map:
      case AlbumManager.CreateAlbumResult.Created(album) => album
      case value                                         => fail(message = s"Expected runtime album: $value")

  private def createChapter(managers: AppManagers[IO], album: AlbumId, workspace: AlbumWorkspaceManager.GetResult, source: PhotoSource) =
    val countries = workspace match
      case found: AlbumWorkspaceManager.GetResult.Found => NonEmptyList.one(found.countries.head.id)
      case value                                        => fail(message = s"Expected country catalog: $value")
    managers.chapters.create(album, name = "Home", countries = countries, ChapterMetadataManager.Period.AlbumYear, source).map:
      case ChapterMetadataManager.CreateResult.Created(id, _) => id
      case value                                              => fail(message = s"Expected runtime chapter: $value")

  private def createCamera(managers: AppManagers[IO], code: String) =
    val exif: CameraExifCode = code.refineUnsafe
    managers.gear.createCamera(exif, name = "Body", CameraKind.InterchangeableLens(LensMount.SonyE, SensorFormat.ApsC)).map:
      case GearManager.CameraCreateResult.Created(value) => value
      case value                                         => fail(message = s"Expected runtime camera: $value")

  private def createLens(managers: AppManagers[IO], code: String) =
    val exif: LensExifCode = code.refineUnsafe
    managers.gear.createLens(exif, name = "Lens", LensMount.SonyE, SensorFormat.FullFrame).map:
      case GearManager.LensCreateResult.Created(value) => value
      case value                                       => fail(message = s"Expected runtime lens: $value")

  private def buildDirectory =
    val acquire = IO.blocking:
      val root = Files.createDirectories(Path.of("target", s"gear-runtime-${UUID.randomUUID()}")).toAbsolutePath
      val _    = Files.createDirectory(root.resolve("source"))
      root
    Resource.make(acquire)(root => IO.blocking(Files.delete(root.resolve("source"))) >> IO.blocking(Files.delete(root)))

  private def buildDatabase(root: Path) =
    val path     = root.resolve("runtime.sqlite")
    val database = Resource.make(path.pure[IO])(value => IO.blocking(Files.deleteIfExists(value)).void)
    database.evalTap(value => FlywayMigrator.make[IO](value).use(_.migrate).void)
      .flatMap(value => SqliteConnection.make[IO](SqliteConfig(value, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor)
