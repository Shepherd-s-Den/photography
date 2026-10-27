package photography.usecases

import cats.data.Kleisli
import cats.Id
import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{PhotoRepository, PhotoStorage, ThumbnailStorage}
import photography.usecases.ChapterPhotoImport.{Scheduling, Status}
import java.util.UUID

final class ChapterPhotoImportComponentTest extends CatsEffectSuite:
  private type Db[A] = Kleisli[IO, Unit, A]
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given Transaction[Db, IO] with
    override def run[A](program: Db[A]): IO[A]     = program.run(())
    override def resolve[A](program: Db[A]): IO[A] = program.run(())
  private val chapter             = ChapterId(new UUID(0L, 1L))
  private val source: PhotoSource = "/photos/a"
  private val encoded             = Vector(1.toByte).refineEither[ThumbnailBytesConstraint].fold(message => fail(message), identity)
  private val image               = EncodedThumbnail(encoded, ThumbnailDimensions(width = 1, height = 1), ThumbnailQuality.Standard)
  private val content             = PhotoContent(Vector(1.toByte).refineEither[MinLength[1]].fold(message => fail(message), identity))
  private val metadata            = PhotoMetadata(hash = "source", maybeCapturedLocal = None, PhotoDimensions(width = 1, height = 1))

  test("should coalesce overlapping requests and allow another pass after completion"):
    val refs = Refs()

    for
      mutex   <- Mutex[IO]
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      storage = buildStorage(refs, _ => entered.complete(()).void >> release.get.as(PhotoStorage.DiscoveryResult.Failed))
      _ <- buildImport(refs, storage)(using mutex).use: imports =>
        for
          initial <- mutex.lock.surround(imports.prepare(chapter, source))
          queued  <- imports.launch(initial)
          _       <- entered.get
          overlap <- mutex.lock.surround(imports.prepare(chapter, source))
          shared  <- imports.launch(overlap)
          active  <- imports.status(chapter)
          _       <- release.complete(())
          _       <- awaitFinished(imports)
          retry   <- mutex.lock.surround(imports.prepare(chapter, source))
          retried <- imports.launch(retry)
          _       <- awaitFinished(imports)
          actual  <- refs.scans.get
        yield
          assertEquals(queued, Scheduling.Queued)
          assertEquals(shared, Scheduling.Coalesced)
          assertEquals(active, Status.Running)
          assertEquals(retried, Scheduling.Queued)
          assertEquals(actual, Vector(source, source))
    yield ()

  test("should supersede A to B to A without waiting for the old scan or accepting its completion"):
    val refs = Refs()

    for
      mutex   <- Mutex[IO]
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      storage = buildStorage(refs, _ => IO.uncancelable(_ => entered.complete(()).void >> release.get.as(PhotoStorage.DiscoveryResult.Failed)))
      _ <- buildImport(refs, storage)(using mutex).use: imports =>
        for
          original  <- mutex.lock.surround(imports.prepare(chapter, source))
          _         <- imports.launch(original)
          _         <- entered.get
          changed   <- mutex.lock.surround(imports.prepare(chapter, source = "/photos/b"))
          latest    <- mutex.lock.surround(imports.prepare(chapter, source))
          obsolete  <- imports.launch(changed)
          queued    <- imports.launch(latest)
          duplicate <- imports.launch(original)
          _         <- release.complete(())
          _         <- awaitFinished(imports)
          actual    <- refs.scans.get
        yield
          assertEquals(obsolete, Scheduling.Coalesced)
          assertEquals(duplicate, Scheduling.Coalesced)
          assertEquals(queued, Scheduling.Queued)
          assertEquals(actual, Vector(source, source))
    yield ()

  test("should authorize current writes and compensate an old stored thumbnail after A to B to A"):
    Vector(false, true).traverse_ { supersede =>
      val refs                 = Refs()
      val completed            = Status.Finished(PhotoManager.RefreshResult(unsupportedCount = 0, issues = Vector.empty[PhotoManager.Issue]))
      val expectedBeforeLaunch = if supersede then Status.Queued else completed
      val synchronization      = (Mutex[IO], Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit]).tupled

      synchronization.flatMap { case (mutex, stored, release, launchLatest) =>
        val storage = buildPublicationStorage(refs, stored = stored, release = release)
        val program = buildImport(refs, storage)(using mutex).use: imports =>
          val changeSource = mutex.lock.surround(imports.prepare(chapter, source = "/photos/b") >> imports.prepare(chapter, source))

          for
            idle         <- imports.status(chapter)
            request      <- mutex.lock.surround(imports.prepare(chapter, source))
            _            <- imports.launch(request)
            _            <- stored.get
            latest       <- if supersede then changeSource else request.pure[IO]
            _            <- launchLatest.get
            beforeLaunch <- imports.status(chapter)
            _            <- if supersede then imports.launch(latest).void else IO.unit
            _            <- awaitFinished(imports)
            status       <- imports.status(chapter)
            written      <- refs.saves.get
            deleted      <- refs.removed.get
          yield
            assertEquals(idle, Status.Idle)
            assertEquals(beforeLaunch, expectedBeforeLaunch)
            assertEquals(written.map(_.original.source), Vector(source))
            assertEquals(deleted.size, if supersede then 1 else 0)
            assertEquals(status, completed)

        for
          control <- TestControl.execute(program)
          _       <- control.tick
          _       <- release.complete(())
          _       <- control.tick
          _       <- launchLatest.complete(())
          _       <- control.tick
          result  <- control.results
        yield assertEquals(result, Outcome.succeeded[Id, Throwable, Unit](()).some)
      }
    }

  test("should report launch failure after its application resource is closed"):
    val refs = Refs()

    for
      mutex <- Mutex[IO]
      storage = buildStorage(refs, _ => PhotoStorage.DiscoveryResult.Failed.pure[IO])
      imports <- buildImport(refs, storage)(using mutex).use(_.pure[IO])
      request <- mutex.lock.surround(imports.prepare(chapter, source))
      result  <- imports.launch(request)
      status  <- imports.status(chapter)
    yield
      assertEquals(result, Scheduling.Failed)
      assertEquals(status, Status.LaunchFailed)

  private def awaitFinished(imports: ChapterPhotoImport[IO]) =
    val finished = (IO.cede >> imports.status(chapter)).map:
      case _: Status.Finished => true
      case _                  => false
    finished.iterateUntil(identity).void

  final private case class Refs():
    val scans: Ref[IO, Vector[PhotoSource]]         = Ref.unsafe(Vector.empty)
    val saves: Ref[IO, Vector[Photo]]               = Ref.unsafe(Vector.empty)
    val removed: Ref[IO, Vector[ThumbnailLocation]] = Ref.unsafe(Vector.empty)

  private def buildImport(refs: Refs, storage: PhotoStorage[IO])(using Mutex[IO]) =
    given IdGenerator[IO] with
      private val next                  = Ref.unsafe[IO, Long](10L)
      override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(index => new UUID(0L, index))
    val photos = new PhotoRepository[Db]:
      override def listChapter(id: ChapterId): Db[PhotoRepository.ListResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.ListResult](refs.saves.get.map(photos => PhotoRepository.ListResult.Listed(photos = photos)))
      override def save(photo: Photo): Db[PhotoRepository.WriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.WriteResult](refs.saves.update(_ :+ photo).as(PhotoRepository.WriteResult.Applied))
      override def remove(id: PhotoId): Db[PhotoRepository.WriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.WriteResult](PhotoRepository.WriteResult.Applied.pure[IO])
      override def recordWrite(intent: PhotoWriteIntent): Db[PhotoRepository.RecordWriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.RecordWriteResult](PhotoRepository.RecordWriteResult.Recorded.pure[IO])
      override def completeWrite(id: PhotoWriteIntentId): Db[PhotoRepository.CompleteWriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.CompleteWriteResult](PhotoRepository.CompleteWriteResult.Completed.pure[IO])
      override def listUnfinishedWrites: Db[PhotoRepository.ListUnfinishedWritesResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.ListUnfinishedWritesResult](PhotoRepository.ListUnfinishedWritesResult.Failed.pure[IO])
    val generator = new PhotoThumbnailGenerator[IO]:
      override def quality: ThumbnailQuality                                                     = ThumbnailQuality.Standard
      override def generate(content: PhotoContent): IO[PhotoThumbnailGenerator.GenerationResult] =
        PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO]
    ChapterPhotoImport.make(storage, photos, generator)

  private def buildStorage(refs: Refs, discover: PhotoSource => IO[PhotoStorage.DiscoveryResult]) =
    new PhotoStorage[IO]:
      override def discoverOriginals(value: PhotoSource): IO[PhotoStorage.DiscoveryResult]    = refs.scans.update(_ :+ value) >> discover(value)
      override def readOriginal(location: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] =
        IO.raiseError[PhotoStorage.ReadResult](new AssertionError("Unexpected read"))
      override def storeThumbnail(location: ThumbnailLocation, image: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        IO.raiseError[ThumbnailStorage.StoreResult](new AssertionError("Unexpected store"))
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        IO.raiseError[ThumbnailStorage.RemoveResult](new AssertionError("Unexpected removal"))

  private def buildPublicationStorage(refs: Refs, stored: Deferred[IO, Unit], release: Deferred[IO, Unit]) =
    new PhotoStorage[IO]:
      override def discoverOriginals(value: PhotoSource): IO[PhotoStorage.DiscoveryResult] =
        val originals = Vector(OriginalPhotoLocation(value, filename = "one.jpg"))
        refs.scans.update(_ :+ value).as(PhotoStorage.DiscoveryResult.Complete(originals, unsupportedCount = 0))
      override def readOriginal(location: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] =
        PhotoStorage.ReadResult.Read(content, metadata).pure[IO]
      override def storeThumbnail(location: ThumbnailLocation, value: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
        val thumbnail = Thumbnail(location, version = "version", value.dimensions, value.quality, bytes = 1)
        stored.complete(()).void >> release.get.as(ThumbnailStorage.StoreResult.Stored(thumbnail))
      override def removeThumbnail(location: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
        refs.removed.update(_ :+ location).as(ThumbnailStorage.RemoveResult.Removed)
