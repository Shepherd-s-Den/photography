package photography.usecases.gear

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.*
import photography.ports.gear.{ChapterGearRepository, GearCatalogRepository}
import photography.usecases.gear.ChapterGearManager.*
import java.util.UUID
import scala.concurrent.duration.*

final class ChapterGearManagerComponentTest extends CatsEffectSuite:
  private val album     = AlbumId(new UUID(0L, 1L))
  private val chapter   = ChapterId(new UUID(0L, 2L))
  private val selection = Vector(CameraSelection(CameraId(new UUID(0L, 3L)), CameraUse.FixedLens))
  private val catalog   = GearCatalog(cameras = Vector.empty[Camera], lenses = Vector.empty[Lens])

  test("should expose current gear choices and catalog failure through the chapter editing boundary"):
    val cases = Vector(
      GearCatalogRepository.ReadResult.Found(catalog) -> CatalogResult.Found(catalog),
      GearCatalogRepository.ReadResult.Failed         -> CatalogResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), catalogResult = response).flatMap(_.readCatalog).map(assertEquals(_, expected))
    }

  test("should read authoritative selections without replacing missing or failed reads with empty gear"):
    val cases = Vector(
      ChapterGearRepository.ReadResult.Found(selection) -> ReadResult.Found(selection),
      ChapterGearRepository.ReadResult.NotFound         -> ReadResult.NotFound,
      ChapterGearRepository.ReadResult.Failed           -> ReadResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), readResult = response).flatMap(_.read(album, chapter)).map(assertEquals(_, expected))
    }

  test("should validate current catalog then replace the complete ordered selection once with explicit outcomes"):
    val cases = Vector(
      ChapterGearRepository.WriteResult.Saved     -> ReplaceResult.Saved,
      ChapterGearRepository.WriteResult.NotFound  -> ReplaceResult.NotFound,
      ChapterGearRepository.WriteResult.Failed    -> ReplaceResult.Failed,
      ChapterGearRepository.WriteResult.Uncertain -> ReplaceResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()
      for
        manager <- buildManager(refs, writeResult = response)
        result  <- manager.replace(album, chapter, selection = selection)
        events  <- refs.events.get
      yield
        assertEquals(result, expected)
        assertEquals(events, Vector("catalog", "replace"))
    }

  test("should reject invalid gear or unavailable catalog before any write"):
    val reason = GearCompatibility.Failure.IncompatibleLens
    val cases  = Vector(
      (GearCatalogRepository.ReadResult.Found(catalog), GearCompatibility.ValidationResult.Rejected(reason), ReplaceResult.Rejected(reason)),
      (GearCatalogRepository.ReadResult.Failed, GearCompatibility.ValidationResult.Accepted, ReplaceResult.Failed)
    )

    cases.traverse_ { case (reading, validation, expected) =>
      val refs = Refs()
      for
        manager <- buildManager(refs, catalogResult = reading, validation = validation)
        result  <- manager.replace(album, chapter, selection = selection)
        events  <- refs.events.get
      yield
        assertEquals(result, expected)
        assert(!events.contains("replace"))
    }

  test("should replace empty or ordered selections without reordering or deriving associations from position"):
    val second     = CameraSelection(CameraId(new UUID(0L, 4L)), CameraUse.FixedLens)
    val selections = Vector(Vector.empty[CameraSelection], second +: selection)

    selections.traverse_ { selected =>
      buildManager(Refs(), selected = selected).flatMap(_.replace(album, chapter, selection = selected)).map(assertEquals(_, ReplaceResult.Saved))
    }

  test("should coordinate the entire decision and replacement across callers and finish a handed-off mutation before cancellation"):
    TestControl.executeEmbed:
      val refs = Refs()
      for
        mutex      <- Mutex[IO]
        reading    <- Deferred[IO, Unit]
        release    <- Deferred[IO, Unit]
        first      <- buildManager(refs, maybeMutex = mutex.some, beforeCatalog = reading.complete(()).void >> release.get)
        second     <- buildManager(refs, maybeMutex = mutex.some)
        pending    <- first.replace(album, chapter, selection = selection).start
        _          <- reading.get
        cancelling <- pending.cancel.start
        next       <- second.replace(album, chapter, selection = selection).start
        _          <- IO.sleep(1.millis)
        before     <- refs.events.get
        _          <- release.complete(())
        _          <- pending.join
        _          <- cancelling.joinWithNever
        result     <- next.joinWithNever
        after      <- refs.events.get
      yield
        assertEquals(before, Vector("catalog"))
        assertEquals(result, ReplaceResult.Saved)
        assertEquals(after, Vector("catalog", "replace", "catalog", "replace"))

  final private case class Refs():
    val events: Ref[IO, Vector[String]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      readResult: ChapterGearRepository.ReadResult = ChapterGearRepository.ReadResult.Found(selection),
      writeResult: ChapterGearRepository.WriteResult = ChapterGearRepository.WriteResult.Saved,
      catalogResult: GearCatalogRepository.ReadResult = GearCatalogRepository.ReadResult.Found(catalog),
      validation: GearCompatibility.ValidationResult = GearCompatibility.ValidationResult.Accepted,
      maybeMutex: Option[Mutex[IO]] = None,
      beforeCatalog: IO[Unit] = IO.unit,
      selected: GearSelection = selection
  ) =
    val repository = new ChapterGearRepository[IO]:
      override def read(owner: AlbumId, id: ChapterId): IO[ChapterGearRepository.ReadResult] =
        IO(assertEquals((owner, id), (album, chapter))).as(readResult)
      override def replace(owner: AlbumId, id: ChapterId, values: GearSelection): IO[ChapterGearRepository.WriteResult] =
        IO(assertEquals((owner, id, values), (album, chapter, selected))) >> refs.events.update(_ :+ "replace").as(writeResult)
    val inventory = new GearCatalogRepository[IO]:
      override def read: IO[GearCatalogRepository.ReadResult] = refs.events.update(_ :+ "catalog") >> beforeCatalog.as(catalogResult)
    val compatibility = new GearCompatibility:
      override def validate(values: GearCatalog, requested: GearSelection): GearCompatibility.ValidationResult =
        assertEquals((values, requested), (catalog, selected))
        validation
    maybeMutex.fold(Mutex[IO])(_.pure[IO]).map { mutex =>
      given Mutex[IO] = mutex
      ChapterGearManager.make(inventory, repository, compatibility)
    }
  // scalafix:on DisableSyntax.defaultArgs
