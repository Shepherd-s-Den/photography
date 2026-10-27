package photography.usecases

import cats.data.Kleisli
import cats.effect.{Deferred, IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.collection.MinLength
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{PhotoRepository, PhotoStorage}
import photography.usecases.PhotoManager.*
import java.util.UUID

final class PhotoManagerComponentTest extends CatsEffectSuite:
  private type Db[A] = Kleisli[IO, Unit, A]
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = new UUID(0L, 9L).pure[IO]
  private val chapter   = ChapterId(new UUID(0L, 1L))
  private val original  = OriginalPhotoLocation(source = "/photos/trip", filename = "one.jpg")
  private val metadata  = PhotoMetadata(hash = "original", maybeCapturedLocal = None, PhotoDimensions(width = 120, height = 80))
  private val content   = PhotoContent(Vector(1.toByte).refineEither[MinLength[1]].fold[PhotoBytes](message => fail(message), identity))
  private val encoded   = Vector(1.toByte).refineEither[ThumbnailBytesConstraint].fold[ThumbnailBytes](message => fail(message), identity)
  private val image     = EncodedThumbnail(encoded, ThumbnailDimensions(width = 60, height = 40), ThumbnailQuality.Standard)
  private val thumbnail = Thumbnail(ThumbnailLocation(new UUID(0L, 3L)), version = "version", image.dimensions, image.quality, bytes = 1)
  private val stored    = thumbnail.copy(location = ThumbnailLocation(new UUID(0L, 9L)))
  private val existing  = Photo(PhotoId(new UUID(0L, 4L)), chapter, original, metadata, thumbnail, maybeCaption = "Retained".some)
  private val complete  = PhotoStorage.DiscoveryResult.Complete(Vector(original), unsupportedCount = 2)
  private val absent    = PhotoStorage.DiscoveryResult.Complete(Vector.empty[OriginalPhotoLocation], unsupportedCount = 0)
  private val read      = PhotoStorage.ReadResult.Read(content, metadata)
  private val error     = new RuntimeException("boundary failed")

  test("should await publication, preserve identity and caption, and retire only the previous thumbnail"):
    Vector(
      (metadata.copy(hash = "changed"), ThumbnailQuality.Standard),
      (metadata, ThumbnailQuality.Low)
    ).traverse_ { case (changed, previousQuality) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val save = (photo: Photo) =>
          IO(assertEquals(photo, existing.copy(metadata = changed, thumbnail = stored))).as(PhotoRepository.WriteResult.Applied)
        val previous = existing.copy(thumbnail = thumbnail.copy(quality = previousQuality))
        val manager  = buildManager(
          events,
          readResult = PhotoStorage.ReadResult.Read(content, changed).pure[IO],
          existingPhotos = Vector(previous),
          save = save
        )
        manager.refresh(chapter, original.source).flatMap: result =>
          events.get.map: actual =>
            assertEquals(result.issues, Vector.empty[Issue])
            assertEquals(result.unsupportedCount, 2)
            assertEquals(actual, Vector("transaction", "read", "generate", "store", "transaction", s"remove:${thumbnail.location.value}"))
    }

  test("should generate new identities and skip unchanged hashes at the selected quality"):
    Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
      val save = (photo: Photo) =>
        IO(assertEquals(photo, Photo(PhotoId(new UUID(0L, 9L)), chapter, original, metadata, stored, maybeCaption = None)))
          .as(PhotoRepository.WriteResult.Applied)
      for
        fresh     <- buildManager(events, existingPhotos = Vector.empty[Photo], save = save).refresh(chapter, original.source)
        _         <- events.set(Vector.empty)
        unchanged <- buildManager(events).refresh(chapter, original.source)
        actual    <- events.get
      yield
        assertEquals(fresh.issues, Vector.empty[Issue])
        assertEquals(unchanged.issues, Vector.empty[Issue])
        assertEquals(actual, Vector("transaction", "read"))

  test("should delete absent photos only after completed enumeration and successful removal"):
    Vector(
      (PhotoRepository.WriteResult.Applied, Vector.empty[Issue], true),
      (PhotoRepository.WriteResult.Failed, Vector(Issue.Persistence), false)
    ).traverse_ { case (result, issues, removesFile) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val remove = (id: PhotoId) => IO(assertEquals(id, existing.id)).as(result)
        buildManager(events, discovery = absent.pure[IO], remove = remove).refresh(chapter, original.source).flatMap: actual =>
          events.get.map: recorded =>
            assertEquals(actual.issues, issues)
            assertEquals(recorded.contains(s"remove:${thumbnail.location.value}"), removesFile)
    }

  test("should retain inventory when discovery or inventory reading fails"):
    Vector(
      (PhotoStorage.DiscoveryResult.Failed.pure[IO], PhotoRepository.ListResult.Listed(Vector(existing)).pure[IO], Issue.Discovery),
      (IO.raiseError[PhotoStorage.DiscoveryResult](error), PhotoRepository.ListResult.Failed.pure[IO], Issue.Unexpected),
      (complete.pure[IO], PhotoRepository.ListResult.Failed.pure[IO], Issue.Persistence),
      (complete.pure[IO], IO.raiseError[PhotoRepository.ListResult](error), Issue.Persistence)
    ).traverse_ { case (discovery, listed, issue) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        buildManager(events, discovery = discovery, maybeListed = listed.some).refresh(chapter, original.source).flatMap: actual =>
          events.get.map: recorded =>
            assertEquals(actual.issues, Vector(issue))
            assert(!recorded.exists(_.startsWith("remove:")))
    }

  test("should retain previous photos and continue after individual read or generation failures"):
    Vector(
      (PhotoStorage.ReadResult.Failed.pure[IO], PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO], Issue.Read(original.filename)),
      (
        PhotoStorage.ReadResult.Unstable.pure[IO],
        PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO],
        Issue.Unstable(original.filename)
      ),
      (
        PhotoStorage.ReadResult.Undecodable.pure[IO],
        PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO],
        Issue.Undecodable(original.filename)
      ),
      (IO.raiseError[PhotoStorage.ReadResult](error), PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO], Issue.Unexpected),
      (read.pure[IO], PhotoThumbnailGenerator.GenerationResult.Failed.pure[IO], Issue.Generation(original.filename))
    ).traverse_ { case (reading, generation, issue) =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val discovery = PhotoStorage.DiscoveryResult.Complete(Vector(original, original.copy(filename = "two.jpg")), unsupportedCount = 0)
        buildManager(events, discovery = discovery.pure[IO], readResult = reading, generation = generation, existingPhotos = Vector.empty[Photo])
          .refresh(chapter, original.source).flatMap: actual =>
            events.get.map: recorded =>
              assertEquals(actual.issues.size, 2)
              assertEquals(actual.issues.headOption, issue.some)
              assertEquals(recorded.count(_ === "read"), 2)
              assert(!recorded.contains("store"))
    }

  test("should compensate rejected or failed database writes and expose cleanup failures"):
    Vector(
      (PhotoRepository.WriteResult.Failed.pure[IO], Vector(Issue.Persistence)),
      (IO.raiseError[PhotoRepository.WriteResult](error), Vector(Issue.Persistence))
    ).traverse_ { case (writing, issues) =>
      Vector(
        (PhotoStorage.RemoveResult.Removed.pure[IO], Vector.empty[Issue]),
        (PhotoStorage.RemoveResult.Failed.pure[IO], Vector(Issue.Cleanup(stored.location))),
        (IO.raiseError[PhotoStorage.RemoveResult](error), Vector(Issue.Cleanup(stored.location)))
      )
        .traverse_ { case (cleaning, cleanupIssues) =>
          Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
            val manager = buildManager(events, existingPhotos = Vector.empty[Photo], save = _ => writing, cleaning = cleaning)
            manager.refresh(chapter, original.source).flatMap: actual =>
              events.get.map: recorded =>
                assertEquals(actual.issues, issues ++ cleanupIssues)
                assertEquals(recorded.lastOption, s"remove:${stored.location.value}".some)
        }
    }

  test("should expose storage failures without writes, and cleanup failures after successful persistence"):
    Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
      val storeFailure   = buildManager(events, existingPhotos = Vector.empty[Photo], storage = PhotoStorage.StoreResult.Failed.pure[IO])
      val cleanupFailure = buildManager(events, discovery = absent.pure[IO], cleaning = PhotoStorage.RemoveResult.Failed.pure[IO])
      val removeFailure  = buildManager(events, discovery = absent.pure[IO], remove = _ => IO.raiseError[PhotoRepository.WriteResult](error))
      for
        storage      <- storeFailure.refresh(chapter, original.source)
        recorded     <- events.get
        cleanup      <- cleanupFailure.refresh(chapter, original.source)
        failedDelete <- removeFailure.refresh(chapter, original.source)
      yield
        assertEquals(storage.issues, Vector(Issue.Storage))
        assertEquals(recorded.count(_ === "transaction"), 1)
        assertEquals(cleanup.issues, Vector(Issue.Cleanup(thumbnail.location)))
        assertEquals(failedDelete.issues, Vector(Issue.Persistence))

  test("should compensate partial storage failures at their preallocated location and preserve cleanup errors"):
    Vector(PhotoStorage.StoreResult.Failed.pure[IO], IO.raiseError[PhotoStorage.StoreResult](error)).traverse_ { storage =>
      Ref.of[IO, Vector[String]](Vector.empty).flatMap: events =>
        val reading = PhotoStorage.ReadResult.Read(content, metadata.copy(hash = "changed")).pure[IO]
        val manager = buildManager(events, storage = storage, cleaning = PhotoStorage.RemoveResult.Failed.pure[IO], readResult = reading)
        manager.refresh(chapter, original.source).flatMap: result =>
          events.get.map: recorded =>
            assertEquals(result.issues, Vector(Issue.Storage, Issue.Cleanup(stored.location)))
            assertEquals(recorded.lastOption, s"remove:${stored.location.value}".some)
            assert(!recorded.contains(s"remove:${thumbnail.location.value}"))
            assertEquals(recorded.count(_ === "transaction"), 1)
    }

  test("should finish compensating a stored thumbnail before honoring cancellation"):
    (Ref.of[IO, Vector[String]](Vector.empty), Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap:
      case (events, saving, release) =>
        val save    = (_: Photo) => saving.complete(()).void >> release.get.as(PhotoRepository.WriteResult.Failed)
        val manager = buildManager(events, existingPhotos = Vector.empty[Photo], save = save)
        for
          refresh      <- manager.refresh(chapter, original.source).start
          _            <- saving.get
          cancellation <- refresh.cancel.start
          _            <- IO.cede
          _            <- release.complete(())
          _            <- cancellation.join
          result       <- refresh.join
          recorded     <- events.get
        yield
          assert(result.isCanceled)
          assertEquals(recorded.lastOption, s"remove:${stored.location.value}".some)

  test("should commit and retire the previous thumbnail before honoring publication cancellation"):
    (Ref.of[IO, Vector[String]](Vector.empty), Deferred[IO, Unit], Deferred[IO, Unit]).tupled.flatMap:
      case (events, saving, release) =>
        val save = (photo: Photo) =>
          IO(assertEquals(photo, existing.copy(metadata = metadata.copy(hash = "changed"), thumbnail = stored))) >>
            saving.complete(()).void >> release.get.as(PhotoRepository.WriteResult.Applied)
        val reading = PhotoStorage.ReadResult.Read(content, metadata.copy(hash = "changed")).pure[IO]
        val manager = buildManager(events, save = save, readResult = reading)
        for
          refresh      <- manager.refresh(chapter, original.source).start
          _            <- saving.get
          cancellation <- refresh.cancel.start
          _            <- IO.cede
          _            <- release.complete(())
          _            <- cancellation.join
          result       <- refresh.join
          recorded     <- events.get
        yield
          assert(result.isCanceled)
          assertEquals(recorded.lastOption, s"remove:${thumbnail.location.value}".some)
          assert(!recorded.contains(s"remove:${stored.location.value}"))

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      events: Ref[IO, Vector[String]],
      discovery: IO[PhotoStorage.DiscoveryResult] = complete.pure[IO],
      readResult: IO[PhotoStorage.ReadResult] = read.pure[IO],
      generation: IO[PhotoThumbnailGenerator.GenerationResult] = PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO],
      storage: IO[PhotoStorage.StoreResult] = PhotoStorage.StoreResult.Stored(stored).pure[IO],
      cleaning: IO[PhotoStorage.RemoveResult] = PhotoStorage.RemoveResult.Removed.pure[IO],
      existingPhotos: Vector[Photo] = Vector(existing),
      maybeListed: Option[IO[PhotoRepository.ListResult]] = None,
      quality: ThumbnailQuality = ThumbnailQuality.Standard,
      save: Photo => IO[PhotoRepository.WriteResult] = _ => PhotoRepository.WriteResult.Applied.pure[IO],
      remove: PhotoId => IO[PhotoRepository.WriteResult] = _ => PhotoRepository.WriteResult.Applied.pure[IO]
  ): PhotoManager[IO] =
    given Transaction[Db, IO] with
      override def run[A](program: Db[A]): IO[A] = events.update(_ :+ "transaction") >> program.run(())
    val photoStorage = new PhotoStorage[IO]:
      override def discoverOriginals(source: PhotoSource): IO[PhotoStorage.DiscoveryResult] = IO(assertEquals(source, original.source)) >>
        discovery
      override def readOriginal(location: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] = events.update(_ :+ "read") >> readResult
      override def storeThumbnail(location: ThumbnailLocation, value: EncodedThumbnail): IO[PhotoStorage.StoreResult] =
        IO(assertEquals(location, stored.location)) >> IO(assertEquals(value, image)) >> events.update(_ :+ "store") >> storage
      override def removeThumbnail(location: ThumbnailLocation): IO[PhotoStorage.RemoveResult] =
        events.update(_ :+ s"remove:${location.value}") >> cleaning
    val writePhoto  = save
    val removePhoto = remove
    val repository  = new PhotoRepository[Db]:
      override def listChapter(id: ChapterId): Db[PhotoRepository.ListResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.ListResult](IO(assertEquals(id, chapter)) >>
          maybeListed.getOrElse(PhotoRepository.ListResult.Listed(existingPhotos).pure[IO]))
      override def save(photo: Photo): Db[PhotoRepository.WriteResult]     = Kleisli.liftF[IO, Unit, PhotoRepository.WriteResult](writePhoto(photo))
      override def remove(photo: PhotoId): Db[PhotoRepository.WriteResult] =
        Kleisli.liftF[IO, Unit, PhotoRepository.WriteResult](removePhoto(photo))
    val selectedQuality = quality
    val generator       = new PhotoThumbnailGenerator[IO]:
      override def quality: ThumbnailQuality                                                   = selectedQuality
      override def generate(value: PhotoContent): IO[PhotoThumbnailGenerator.GenerationResult] =
        IO(assertEquals(value, content)) >> events.update(_ :+ "generate") >> generation
    PhotoManager.make(photoStorage, repository, generator)
  // scalafix:on DisableSyntax.defaultArgs
