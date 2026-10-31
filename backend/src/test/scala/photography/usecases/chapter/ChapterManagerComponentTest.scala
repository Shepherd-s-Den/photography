package photography.usecases.chapter

import cats.data.NonEmptyList
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import munit.CatsEffectSuite
import photography.domain.album.*
import photography.capabilities.Transaction
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.gear.*
import photography.ports.chapter.ChapterRepository
import photography.usecases.gear.GearManager
import photography.usecases.chapter.ChapterManager.*
import java.util.UUID
import scala.concurrent.duration.*

final class ChapterManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val album        = AlbumId(new UUID(0L, 1L))
  private val chapter      = ChapterId(new UUID(0L, 2L))
  private val selection    = Vector(CameraSelection(CameraId(new UUID(0L, 3L)), CameraUse.FixedLens))
  private val catalog      = GearCatalog(cameras = Vector.empty[Camera], lenses = Vector.empty[Lens])

  test("should read saved chapter gear without disguising missing or failed reads as empty selections"):
    val cases = Vector(
      ChapterRepository.GearReadResult.Found(selection = selection)                     -> GearReadResult.Found(selection = selection),
      ChapterRepository.GearReadResult.Found(selection = Vector.empty[CameraSelection]) ->
        GearReadResult.Found(selection = Vector.empty[CameraSelection]),
      ChapterRepository.GearReadResult.NotFound -> GearReadResult.NotFound,
      ChapterRepository.GearReadResult.Failed   -> GearReadResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), read = response).flatMap(_.readGear(album, chapter)).map(assertEquals(_, expected))
    }

  test("should replace complete ordered gear once after the shared owner validates the loaded catalog"):
    val cases = Vector(
      ChapterRepository.GearWriteResult.Saved     -> GearReplaceResult.Saved,
      ChapterRepository.GearWriteResult.NotFound  -> GearReplaceResult.NotFound,
      ChapterRepository.GearWriteResult.Failed    -> GearReplaceResult.Failed,
      ChapterRepository.GearWriteResult.Uncertain -> GearReplaceResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, write = response)
        result  <- manager.replaceGear(album, chapter, selection = selection)
        events  <- refs.events.get
      yield
        assertEquals(result, expected)
        assertEquals(events, Vector("catalog", "replace"))
    }

  test("should reject each shared validation failure and unavailable catalogs before persistence"):
    val cases = GearManager.Failure.values.toVector.map { failure =>
      (
        GearManager.CatalogResult.Found(catalog),
        GearManager.ValidationResult.Rejected(failure),
        GearReplaceResult.Rejected(failure),
        Vector("catalog")
      )
    } :+ (GearManager.CatalogResult.Failed, GearManager.ValidationResult.Valid, GearReplaceResult.Failed, Vector("catalog"))

    cases.traverse_ { case (current, validation, expected, expectedEvents) =>
      val refs = Refs()

      for
        manager <- buildManager(refs, current = current, validation = validation)
        result  <- manager.replaceGear(album, chapter, selection = selection)
        events  <- refs.events.get
      yield
        assertEquals(result, expected)
        assertEquals(events, expectedEvents)
    }

  test("should coordinate catalog validation and replacement while preserving cancellation-safe handoff"):
    TestControl.executeEmbed:
      val refs = Refs()

      for
        mutex      <- Mutex[IO]
        reading    <- Deferred[IO, Unit]
        release    <- Deferred[IO, Unit]
        first      <- buildManager(refs, maybeMutex = mutex.some, beforeCatalog = reading.complete(()).void >> release.get)
        second     <- buildManager(refs, maybeMutex = mutex.some)
        pending    <- first.replaceGear(album, chapter, selection = selection).start
        _          <- reading.get
        cancelling <- pending.cancel.start
        waiting    <- second.replaceGear(album, chapter, selection = selection).start
        _          <- IO.sleep(1.millis)
        _          <- waiting.cancel
        before     <- refs.events.get
        _          <- release.complete(())
        _          <- pending.join
        _          <- cancelling.joinWithNever
        result     <- second.replaceGear(album, chapter, selection = selection)
        after      <- refs.events.get
      yield
        assertEquals(before, Vector("catalog"))
        assertEquals(result, GearReplaceResult.Saved)
        assertEquals(after, Vector("catalog", "replace", "catalog", "replace"))

  test("should report failed transaction reads and uncertain write acknowledgements without replay"):
    val failure = new IllegalStateException("Transaction failed").raiseError[IO, Unit]

    for
      manager <- buildManager(Refs(), beforeTransaction = failure)
      read    <- manager.readGear(album, chapter)
      write   <- manager.replaceGear(album, chapter, selection = selection)
    yield
      assertEquals(read, GearReadResult.Failed)
      assertEquals(write, GearReplaceResult.Uncertain)

  final private case class Refs():
    val events: Ref[IO, Vector[String]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      read: ChapterRepository.GearReadResult = ChapterRepository.GearReadResult.Found(selection = selection),
      write: ChapterRepository.GearWriteResult = ChapterRepository.GearWriteResult.Saved,
      current: GearManager.CatalogResult = GearManager.CatalogResult.Found(catalog),
      validation: GearManager.ValidationResult = GearManager.ValidationResult.Valid,
      maybeMutex: Option[Mutex[IO]] = None,
      beforeCatalog: IO[Unit] = IO.unit,
      beforeTransaction: IO[Unit] = IO.unit
  ) =
    given Transaction[IO, IO] with
      override def run[A](program: IO[A]): IO[A]     = beforeTransaction >> program
      override def resolve[A](program: IO[A]): IO[A] = unexpected
    val chapters = new ChapterRepository[IO]:
      override def readGear(owner: AlbumId, id: ChapterId): IO[ChapterRepository.GearReadResult] =
        IO(assertEquals((owner, id), (album, chapter))).as(read)
      override def replaceGear(owner: AlbumId, id: ChapterId, values: GearSelection): IO[ChapterRepository.GearWriteResult] =
        IO(assertEquals((owner, id, values), (album, chapter, selection))) >> refs.events.update(_ :+ "replace").as(write)
      override def readSelections: IO[ChapterRepository.SelectionsReadResult]                                                = unexpected
      override def saveDescription(owner: AlbumId, id: ChapterId, text: String): IO[ChapterRepository.SaveDescriptionResult] = unexpected
      override def findSource(id: ChapterId): IO[ChapterRepository.SourceResult]                                             = unexpected
      override def create(owner: AlbumId, value: ChapterRepository.NewChapter, order: NonEmptyList[ChapterId]): IO[ChapterRepository.CreateResult] =
        unexpected
      override def update(command: ChapterRepository.Update): IO[ChapterRepository.UpdateResult] = unexpected
    val gear = new GearManager[IO]:
      override def readCatalog: IO[GearManager.CatalogResult] = refs.events.update(_ :+ "catalog") >> beforeCatalog.as(current)
      override def validateSelection(current: GearCatalog, values: GearSelection): GearManager.ValidationResult =
        assertEquals((current, values), (catalog, selection))
        validation
      override def createCamera(code: CameraExifCode, name: CameraName, kind: CameraKind): IO[GearManager.CameraCreateResult] = unexpected
      override def updateCamera(camera: Camera): IO[GearManager.CameraUpdateResult]                                           = unexpected
      override def deleteCamera(id: CameraId): IO[GearManager.CameraDeleteResult]                                             = unexpected
      override def createLens(code: LensExifCode, name: LensName, mount: LensMount, coverage: SensorFormat): IO[GearManager.LensCreateResult] =
        unexpected
      override def updateLens(lens: Lens): IO[GearManager.LensUpdateResult] = unexpected
      override def deleteLens(id: LensId): IO[GearManager.LensDeleteResult] = unexpected
    maybeMutex.fold(Mutex[IO])(_.pure[IO]).map { mutex =>
      given Mutex[IO] = mutex
      ChapterManager.make(chapters, gear)
    }
  // scalafix:on DisableSyntax.defaultArgs

  private def unexpected[A]: IO[A] = new AssertionError("Unexpected operation").raiseError[IO, A]
