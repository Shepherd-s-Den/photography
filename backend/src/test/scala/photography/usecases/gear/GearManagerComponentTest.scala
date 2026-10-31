package photography.usecases.gear

import cats.data.NonEmptyVector
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.capabilities.IdGenerator
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.gear.*
import photography.ports.gear.GearRepository
import photography.ports.gear.GearRepository.Usage
import photography.usecases.gear.GearManager.*
import java.util.UUID
import scala.concurrent.duration.*

final class GearManagerComponentTest extends CatsEffectSuite:

  private val sony  = LensMount.SonyE
  private val nikon = LensMount.NikonZ
  private val first =
    Camera(CameraId(new UUID(0L, 1L)), code = "one", friendlyName = "First", CameraKind.InterchangeableLens(mount = sony, sensor = SensorFormat.ApsC))
  private val second        = first.copy(id = CameraId(new UUID(0L, 2L)), code = "two")
  private val fixed         = Camera(CameraId(new UUID(0L, 3L)), code = "fixed", friendlyName = "Fixed", CameraKind.FixedLens)
  private val lens          = Lens(LensId(new UUID(0L, 4L)), code = "lens", friendlyName = "Lens", mount = sony, coverage = SensorFormat.FullFrame)
  private val use           = CameraSelection(first.id, CameraUse.Interchangeable(lenses = NonEmptyVector.one(lens.id)))
  private val apsCKind      = CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.ApsC)
  private val fullFrameKind = CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.FullFrame)
  private val nikonKind     = CameraKind.InterchangeableLens(mount = nikon, sensor = SensorFormat.ApsC)
  private val camera        = Camera(CameraId(new UUID(0L, 1L)), code = "body", friendlyName = "Body", apsCKind)
  private val apsCLens   = Lens(LensId(new UUID(0L, 2L)), code = "lens", friendlyName = "Lens", mount = LensMount.SonyE, coverage = SensorFormat.ApsC)
  private val sharedLens =
    Lens(LensId(new UUID(0L, 1L)), code = "lens", friendlyName = "Lens", mount = LensMount.SonyE, coverage = SensorFormat.FullFrame)
  private val fullFrame = new CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.FullFrame)
  private val album     = AlbumId(new UUID(0L, 1L))
  private val chapter   = ChapterId(new UUID(0L, 2L))
  private val selection = Vector(CameraSelection(fixed.id, CameraUse.FixedLens))
  private val catalog   = GearCatalog(cameras = Vector(first, second, fixed), lenses = Vector(lens))

  test("should allow empty gear, fixed cameras and the same lens selected for multiple compatible bodies in selection order"):
    val catalog    = GearCatalog(cameras = Vector(first, second, fixed), lenses = Vector(lens))
    val selections = Vector(
      Vector.empty[CameraSelection],
      Vector(CameraSelection(fixed.id, CameraUse.FixedLens)),
      Vector(use, use.copy(camera = second.id), CameraSelection(fixed.id, CameraUse.FixedLens))
    )

    selections.traverse_ { selected =>
      val refs = Refs()

      for
        manager <- buildManager(refs, catalogRead = GearRepository.CatalogReadResult.Found(catalog), selected = selected)
        result  <- manager.replaceChapter(album, chapter, selection = selected)
        events  <- refs.events.get
      yield
        assertEquals(result, ChapterReplaceResult.Saved)
        assertEquals(events, Vector("catalog", "replace"))
    }

  test("should require matching mounts and sufficient coverage for every sensor format"):
    val cases =
      for
        mount     <- LensMount.values.toVector
        sensor    <- SensorFormat.values.toVector
        lensMount <- LensMount.values.toVector
        coverage  <- SensorFormat.values.toVector
      yield (mount, sensor, lensMount, coverage)

    cases.traverse_ { case (mount, sensor, lensMount, coverage) =>
      val accepted       = mount === lensMount && (sensor === SensorFormat.ApsC || coverage === SensorFormat.FullFrame)
      val camera         = first.copy(kind = CameraKind.InterchangeableLens(mount = mount, sensor = sensor))
      val candidate      = lens.copy(mount = lensMount, coverage = coverage)
      val expected       = if accepted then ChapterReplaceResult.Saved else ChapterReplaceResult.Rejected(Failure.IncompatibleLens)
      val current        = GearCatalog(cameras = Vector(camera), lenses = Vector(candidate))
      val selected       = Vector(use)
      val refs           = Refs()
      val expectedEvents = if accepted then Vector("catalog", "replace") else Vector("catalog")

      for
        manager <- buildManager(refs, catalogRead = GearRepository.CatalogReadResult.Found(current), selected = selected)
        result  <- manager.replaceChapter(album, chapter, selection = selected)
        events  <- refs.events.get
      yield
        assertEquals(result, expected)
        assertEquals(events, expectedEvents)
    }

  test("should reject unknown, duplicate and wrong-kind selections without reserving lenses globally"):
    val catalog       = GearCatalog(cameras = Vector(first, fixed), lenses = Vector(lens))
    val unknownCamera = CameraId(new UUID(0L, 90L))
    val unknownLens   = LensId(new UUID(0L, 91L))
    val cases         = Vector(
      Vector(use, use)                                                                                -> Failure.DuplicateCamera,
      Vector(use.copy(camera = unknownCamera))                                                        -> Failure.UnknownCamera,
      Vector(use.copy(use = CameraUse.FixedLens))                                                     -> Failure.CameraKindMismatch,
      Vector(use.copy(camera = fixed.id))                                                             -> Failure.CameraKindMismatch,
      Vector(use.copy(use = CameraUse.Interchangeable(lenses = NonEmptyVector.of(lens.id, lens.id)))) -> Failure.DuplicateLens,
      Vector(use.copy(use = CameraUse.Interchangeable(lenses = NonEmptyVector.one(unknownLens))))     -> Failure.UnknownLens
    )

    cases.traverse_ { case (selection, failure) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, catalogRead = GearRepository.CatalogReadResult.Found(catalog), selected = selection)
        result  <- manager.replaceChapter(album, chapter, selection = selection)
        events  <- refs.events.get
      yield
        assertEquals(result, ChapterReplaceResult.Rejected(failure))
        assertEquals(events, Vector("catalog"))
    }

  test("should create a camera with generated identity and expose repository conflicts and uncertainty"):
    val cases = Vector(
      GearRepository.CameraCreateResult.Created   -> CameraCreateResult.Created(camera),
      GearRepository.CameraCreateResult.Conflict  -> CameraCreateResult.Conflict,
      GearRepository.CameraCreateResult.Failed    -> CameraCreateResult.Failed,
      GearRepository.CameraCreateResult.Uncertain -> CameraCreateResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, cameraCreate = response)
        result  <- manager.createCamera(camera.code, camera.friendlyName, camera.kind)
        created <- refs.createdCameras.get
      yield
        assertEquals(result, expected)
        assertEquals(created, Vector(camera))
    }

  test("should protect every saved camera use when editing kind, mount or sensor"):
    val cases = Vector(
      (camera, Usage.Interchangeable(lenses = NonEmptyVector.one(apsCLens)), true),
      (camera.copy(kind = fullFrameKind), Usage.Interchangeable(lenses = NonEmptyVector.one(apsCLens)), false),
      (
        camera.copy(kind = fullFrameKind),
        Usage.Interchangeable(lenses = NonEmptyVector.of(apsCLens.copy(coverage = SensorFormat.FullFrame), apsCLens)),
        false
      ),
      (camera, Usage.Interchangeable(lenses = NonEmptyVector.one(apsCLens.copy(coverage = SensorFormat.FullFrame))), true),
      (camera.copy(kind = nikonKind), Usage.Interchangeable(lenses = NonEmptyVector.one(apsCLens)), false),
      (camera.copy(kind = CameraKind.FixedLens), Usage.Interchangeable(lenses = NonEmptyVector.one(apsCLens)), false),
      (camera.copy(kind = CameraKind.FixedLens), Usage.FixedLens, true),
      (camera, Usage.FixedLens, false),
      (camera.copy(kind = CameraKind.FixedLens), Usage.Unused, true)
    )

    cases.traverse_ { case (candidate, usage, allowed) =>
      val refs     = Refs()
      val expected = if allowed then CameraUpdateResult.Updated else CameraUpdateResult.InvalidatesSelection

      for
        manager <- buildManager(refs, cameraReferences = GearRepository.CameraReferencesResult.Found(usage))
        result  <- manager.updateCamera(candidate)
        updated <- refs.updatedCameras.get
      yield
        assertEquals(result, expected)
        assertEquals(updated, if allowed then Vector(candidate) else Vector.empty[Camera])
    }

  test("should preserve missing and failed reference reads without writing"):
    val cases = Vector(
      GearRepository.CameraReferencesResult.NotFound -> CameraUpdateResult.NotFound,
      GearRepository.CameraReferencesResult.Failed   -> CameraUpdateResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, cameraReferences = response)
        result  <- manager.updateCamera(camera)
        updated <- refs.updatedCameras.get
      yield
        assertEquals(result, expected)
        assertEquals(updated, Vector.empty[Camera])
    }

  test("should expose all update and independent deletion outcomes without repeating mutations"):
    val updates = Vector(
      GearRepository.CameraUpdateResult.Updated   -> CameraUpdateResult.Updated,
      GearRepository.CameraUpdateResult.NotFound  -> CameraUpdateResult.NotFound,
      GearRepository.CameraUpdateResult.Conflict  -> CameraUpdateResult.Conflict,
      GearRepository.CameraUpdateResult.Failed    -> CameraUpdateResult.Failed,
      GearRepository.CameraUpdateResult.Uncertain -> CameraUpdateResult.Uncertain
    )
    val deletes = Vector(
      GearRepository.CameraDeleteResult.Deleted   -> CameraDeleteResult.Deleted,
      GearRepository.CameraDeleteResult.NotFound  -> CameraDeleteResult.NotFound,
      GearRepository.CameraDeleteResult.Used      -> CameraDeleteResult.Used,
      GearRepository.CameraDeleteResult.Failed    -> CameraDeleteResult.Failed,
      GearRepository.CameraDeleteResult.Uncertain -> CameraDeleteResult.Uncertain
    )

    updates.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, cameraUpdate = response)
        result  <- manager.updateCamera(camera)
        writes  <- refs.updatedCameras.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(camera))
    } >> deletes.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, cameraDelete = response)
        result  <- manager.deleteCamera(camera.id)
        deleted <- refs.deletedCameras.get
      yield
        assertEquals(result, expected)
        assertEquals(deleted, Vector(camera.id))
    }

  test("should create an independently identified shared lens without a camera owner"):
    val cases = Vector(
      GearRepository.LensCreateResult.Created   -> LensCreateResult.Created(sharedLens),
      GearRepository.LensCreateResult.Conflict  -> LensCreateResult.Conflict,
      GearRepository.LensCreateResult.Failed    -> LensCreateResult.Failed,
      GearRepository.LensCreateResult.Uncertain -> LensCreateResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, lensCreate = response, generatedId = sharedLens.id.value)
        result  <- manager.createLens(sharedLens.code, sharedLens.friendlyName, sharedLens.mount, sharedLens.coverage)
        created <- refs.createdLenses.get
      yield
        assertEquals(result, expected)
        assertEquals(created, Vector(sharedLens))
    }

  test("should reject edits invalidating any referencing body while allowing coverage expansion and unused edits"):
    val apsC  = new CameraKind.InterchangeableLens(mount = LensMount.SonyE, sensor = SensorFormat.ApsC)
    val cases = Vector(
      (sharedLens, Vector(fullFrame, apsC), true),
      (sharedLens.copy(coverage = SensorFormat.ApsC), Vector(apsC, fullFrame), false),
      (sharedLens.copy(coverage = SensorFormat.ApsC), Vector(apsC), true),
      (sharedLens.copy(mount = LensMount.NikonZ), Vector(fullFrame), false),
      (sharedLens.copy(mount = LensMount.NikonZ), Vector.empty[CameraKind.InterchangeableLens], true)
    )

    cases.traverse_ { case (candidate, cameras, allowed) =>
      val refs       = Refs()
      val expected   = if allowed then LensUpdateResult.Updated else LensUpdateResult.InvalidatesSelection
      val references = GearRepository.LensReferencesResult.Found(cameras = cameras)

      for
        manager <- buildManager(refs, lensReferences = references)
        result  <- manager.updateLens(candidate)
        updated <- refs.updatedLenses.get
      yield
        assertEquals(result, expected)
        assertEquals(updated, if allowed then Vector(candidate) else Vector.empty[Lens])
    }

  test("should preserve missing and failed lens reference reads without writing"):
    val cases = Vector(
      GearRepository.LensReferencesResult.NotFound -> LensUpdateResult.NotFound,
      GearRepository.LensReferencesResult.Failed   -> LensUpdateResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, lensReferences = response)
        result  <- manager.updateLens(sharedLens)
        updated <- refs.updatedLenses.get
      yield
        assertEquals(result, expected)
        assertEquals(updated, Vector.empty[Lens])
    }

  test("should expose all update and independent deletion outcomes without replay"):
    val updates = Vector(
      GearRepository.LensUpdateResult.Updated   -> LensUpdateResult.Updated,
      GearRepository.LensUpdateResult.NotFound  -> LensUpdateResult.NotFound,
      GearRepository.LensUpdateResult.Conflict  -> LensUpdateResult.Conflict,
      GearRepository.LensUpdateResult.Failed    -> LensUpdateResult.Failed,
      GearRepository.LensUpdateResult.Uncertain -> LensUpdateResult.Uncertain
    )
    val deletes = Vector(
      GearRepository.LensDeleteResult.Deleted   -> LensDeleteResult.Deleted,
      GearRepository.LensDeleteResult.NotFound  -> LensDeleteResult.NotFound,
      GearRepository.LensDeleteResult.Used      -> LensDeleteResult.Used,
      GearRepository.LensDeleteResult.Failed    -> LensDeleteResult.Failed,
      GearRepository.LensDeleteResult.Uncertain -> LensDeleteResult.Uncertain
    )

    updates.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, lensUpdate = response)
        result  <- manager.updateLens(sharedLens)
        writes  <- refs.updatedLenses.get
      yield
        assertEquals(result, expected)
        assertEquals(writes, Vector(sharedLens))
    } >> deletes.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, lensDelete = response)
        result  <- manager.deleteLens(sharedLens.id)
        deleted <- refs.deletedLenses.get
      yield
        assertEquals(result, expected)
        assertEquals(deleted, Vector(sharedLens.id))
    }

  test("should expose current gear choices and catalog failure through the chapter editing boundary"):
    val cases = Vector(
      GearRepository.CatalogReadResult.Found(catalog) -> CatalogResult.Found(catalog),
      GearRepository.CatalogReadResult.Failed         -> CatalogResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), catalogRead = response).flatMap(_.readCatalog).map(assertEquals(_, expected))
    }

  test("should read authoritative selections without replacing missing or failed reads with empty gear"):
    val cases = Vector(
      GearRepository.ChapterReadResult.Found(selection = selection) -> ChapterReadResult.Found(selection = selection),
      GearRepository.ChapterReadResult.NotFound                     -> ChapterReadResult.NotFound,
      GearRepository.ChapterReadResult.Failed                       -> ChapterReadResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), chapterRead = response).flatMap(_.readChapter(album, chapter)).map(assertEquals(_, expected))
    }

  test("should validate current catalog then replace the complete ordered selection once with explicit outcomes"):
    val cases = Vector(
      GearRepository.ChapterWriteResult.Saved     -> ChapterReplaceResult.Saved,
      GearRepository.ChapterWriteResult.NotFound  -> ChapterReplaceResult.NotFound,
      GearRepository.ChapterWriteResult.Failed    -> ChapterReplaceResult.Failed,
      GearRepository.ChapterWriteResult.Uncertain -> ChapterReplaceResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, chapterWrite = response)
        result  <- manager.replaceChapter(album, chapter, selection = selection)
        events  <- refs.events.get
      yield
        assertEquals(result, expected)
        assertEquals(events, Vector("catalog", "replace"))
    }

  test("should reject an unavailable catalog before any write"):
    val refs = Refs()

    for
      manager <- buildManager(refs, catalogRead = GearRepository.CatalogReadResult.Failed)
      result  <- manager.replaceChapter(album, chapter, selection = selection)
      events  <- refs.events.get
    yield
      assertEquals(result, ChapterReplaceResult.Failed)
      assertEquals(events, Vector("catalog"))

  test("should coordinate the entire decision and replacement across callers and finish a handed-off mutation before cancellation"):
    TestControl.executeEmbed:
      val refs = Refs()

      for
        mutex      <- Mutex[IO]
        reading    <- Deferred[IO, Unit]
        release    <- Deferred[IO, Unit]
        first      <- buildManager(refs, maybeMutex = mutex.some, beforeCatalog = reading.complete(()).void >> release.get)
        second     <- buildManager(refs, maybeMutex = mutex.some)
        pending    <- first.replaceChapter(album, chapter, selection = selection).start
        _          <- reading.get
        cancelling <- pending.cancel.start
        next       <- second.replaceChapter(album, chapter, selection = selection).start
        _          <- IO.sleep(1.millis)
        before     <- refs.events.get
        _          <- release.complete(())
        _          <- pending.join
        _          <- cancelling.joinWithNever
        result     <- next.joinWithNever
        after      <- refs.events.get
      yield
        assertEquals(before, Vector("catalog"))
        assertEquals(result, ChapterReplaceResult.Saved)
        assertEquals(after, Vector("catalog", "replace", "catalog", "replace"))

  test("should keep waiting camera and lens mutations cancelable without releasing another caller's selection lock"):
    val mutations = Vector[GearManager[IO] => IO[Unit]](
      _.updateCamera(camera).void,
      _.deleteCamera(camera.id).void,
      _.updateLens(sharedLens).void,
      _.deleteLens(sharedLens.id).void
    )

    mutations.traverse_ { mutate =>
      TestControl.executeEmbed:
        val refs = Refs()

        for
          mutex         <- Mutex[IO]
          reading       <- Deferred[IO, Unit]
          release       <- Deferred[IO, Unit]
          first         <- buildManager(refs, maybeMutex = mutex.some, beforeCatalog = reading.complete(()).void >> release.get)
          second        <- buildManager(refs, maybeMutex = mutex.some)
          pending       <- first.replaceChapter(album, chapter, selection = selection).start
          _             <- reading.get
          waiting       <- mutate(second).start
          _             <- IO.sleep(1.millis)
          _             <- waiting.cancel
          cameras       <- refs.updatedCameras.get
          cameraDeletes <- refs.deletedCameras.get
          lenses        <- refs.updatedLenses.get
          lensDeletes   <- refs.deletedLenses.get
          events        <- refs.events.get
          _             <- release.complete(())
          result        <- pending.joinWithNever
          next          <- second.replaceChapter(album, chapter, selection = selection)
        yield
          assertEquals(cameras, Vector.empty[Camera])
          assertEquals(cameraDeletes, Vector.empty[CameraId])
          assertEquals(lenses, Vector.empty[Lens])
          assertEquals(lensDeletes, Vector.empty[LensId])
          assertEquals(events, Vector("catalog"))
          assertEquals(result, ChapterReplaceResult.Saved)
          assertEquals(next, ChapterReplaceResult.Saved)
    }

  final private case class Refs():
    val createdCameras: Ref[IO, Vector[Camera]]   = Ref.unsafe(Vector.empty)
    val updatedCameras: Ref[IO, Vector[Camera]]   = Ref.unsafe(Vector.empty)
    val deletedCameras: Ref[IO, Vector[CameraId]] = Ref.unsafe(Vector.empty)
    val createdLenses: Ref[IO, Vector[Lens]]      = Ref.unsafe(Vector.empty)
    val updatedLenses: Ref[IO, Vector[Lens]]      = Ref.unsafe(Vector.empty)
    val deletedLenses: Ref[IO, Vector[LensId]]    = Ref.unsafe(Vector.empty)
    val events: Ref[IO, Vector[String]]           = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      generatedId: UUID = camera.id.value,
      cameraCreate: GearRepository.CameraCreateResult = GearRepository.CameraCreateResult.Created,
      cameraReferences: GearRepository.CameraReferencesResult = GearRepository.CameraReferencesResult.Found(Usage.Unused),
      cameraUpdate: GearRepository.CameraUpdateResult = GearRepository.CameraUpdateResult.Updated,
      cameraDelete: GearRepository.CameraDeleteResult = GearRepository.CameraDeleteResult.Deleted,
      lensCreate: GearRepository.LensCreateResult = GearRepository.LensCreateResult.Created,
      lensReferences: GearRepository.LensReferencesResult =
        GearRepository.LensReferencesResult.Found(cameras = Vector.empty[CameraKind.InterchangeableLens]),
      lensUpdate: GearRepository.LensUpdateResult = GearRepository.LensUpdateResult.Updated,
      lensDelete: GearRepository.LensDeleteResult = GearRepository.LensDeleteResult.Deleted,
      chapterRead: GearRepository.ChapterReadResult = GearRepository.ChapterReadResult.Found(selection = selection),
      chapterWrite: GearRepository.ChapterWriteResult = GearRepository.ChapterWriteResult.Saved,
      catalogRead: GearRepository.CatalogReadResult = GearRepository.CatalogReadResult.Found(catalog),
      maybeMutex: Option[Mutex[IO]] = None,
      beforeCatalog: IO[Unit] = IO.unit,
      selected: GearSelection = selection
  ) =
    given IdGenerator[IO] with
      override def generateId: IO[UUID] = generatedId.pure[IO]
    val repository = new GearRepository[IO]:
      override def createCamera(value: Camera): IO[GearRepository.CameraCreateResult] = refs.createdCameras.update(_ :+ value).as(cameraCreate)
      override def readCameraReferences(id: CameraId): IO[GearRepository.CameraReferencesResult] =
        IO(assertEquals(id, camera.id)).as(cameraReferences)
      override def updateCamera(value: Camera): IO[GearRepository.CameraUpdateResult]      = refs.updatedCameras.update(_ :+ value).as(cameraUpdate)
      override def deleteCamera(id: CameraId): IO[GearRepository.CameraDeleteResult]       = refs.deletedCameras.update(_ :+ id).as(cameraDelete)
      override def createLens(value: Lens): IO[GearRepository.LensCreateResult]            = refs.createdLenses.update(_ :+ value).as(lensCreate)
      override def readLensReferences(id: LensId): IO[GearRepository.LensReferencesResult] = IO(assertEquals(id, sharedLens.id)).as(lensReferences)
      override def updateLens(value: Lens): IO[GearRepository.LensUpdateResult]            = refs.updatedLenses.update(_ :+ value).as(lensUpdate)
      override def deleteLens(id: LensId): IO[GearRepository.LensDeleteResult]             = refs.deletedLenses.update(_ :+ id).as(lensDelete)
      override def readCatalog: IO[GearRepository.CatalogReadResult] = refs.events.update(_ :+ "catalog") >> beforeCatalog.as(catalogRead)
      override def readChapter(owner: AlbumId, id: ChapterId): IO[GearRepository.ChapterReadResult] =
        IO(assertEquals((owner, id), (album, chapter))).as(chapterRead)
      override def replaceChapter(owner: AlbumId, id: ChapterId, values: GearSelection): IO[GearRepository.ChapterWriteResult] =
        IO(assertEquals((owner, id, values), (album, chapter, selected))) >> refs.events.update(_ :+ "replace").as(chapterWrite)
    maybeMutex.fold(Mutex[IO])(_.pure[IO]).map { mutex =>
      given Mutex[IO] = mutex
      GearManager.make(repository)
    }
  // scalafix:on DisableSyntax.defaultArgs
