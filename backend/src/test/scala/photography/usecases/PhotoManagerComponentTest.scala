package photography.usecases

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Mutex
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.{ChapterRepository, PhotoFileSystem, PhotoPlacementRepository, PhotoRepository}
import photography.usecases.PhotoManager.*
import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.duration.*

final class PhotoManagerComponentTest extends CatsEffectSuite:
  private given Logger[IO]      = Slf4jLogger.getLogger[IO]
  private given Transaction[IO] = new Transaction[IO]:
    override def atomically[E, A](operation: IO[Transaction.Decision[E, A]]): IO[Transaction.Result[E, A]] =
      operation.map:
        case Transaction.Decision.Commit(value)  => Transaction.Result.Committed[E, A](value)
        case Transaction.Decision.Reject(reason) => Transaction.Result.Rejected[E, A](reason)
  private val chapter  = ChapterId(new UUID(0L, 1L))
  private val album    = AlbumId(new UUID(0L, 2L))
  private val location = OriginalPhotoLocation(source = "/photos/trip", relative = "one.jpg")
  private val metadata =
    PhotoMetadata(hash = "original", maybeCapturedLocal = LocalDateTime.of(2025, 6, 1, 12, 0).some, PhotoDimensions(width = 120, height = 80))
  private val content = PhotoContent(bytes = Vector(1.toByte))
  private val image   =
    EncodedThumbnail.from(content, PhotoDimensions(width = 60, height = 40), ThumbnailQuality.Standard).getOrElse(fail("Invalid test image"))
  private val thumbnail = Thumbnail.from(ThumbnailLocation(new UUID(0L, 3L)), version = "version", image.dimensions, image.quality, bytes = 1)
    .getOrElse(fail("Invalid test thumbnail"))
  private val existing = Photo(PhotoId(new UUID(0L, 4L)), chapter, location, metadata, thumbnail, maybeCaption = "Retained".some)
  private val context  = ChapterRepository.PhotoReader.FindResult.Found(album, location.source)

  test("should acknowledge and coalesce Refresh before discovery completes, then publish progressively"):
    val setup = (Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit], Ref.of[IO, Vector[PhotoPublication.Draft]](Vector.empty)).tupled

    setup.flatMap { case (discoveryGate, published, secondGate, drafts) =>
      val second   = location.copy(relative = "two.jpg")
      val discover = discoveryGate.get.as(PhotoFileSystem.DiscoveryResult.Complete(Vector(location, second), unsupportedCount = 2))
      val read     = (original: OriginalPhotoLocation) =>
        (if original === second then secondGate.get else IO.unit).as(PhotoFileSystem.ReadResult.Read(content, metadata))
      val publish = (draft: PhotoPublication.Draft) =>
        drafts.update(_ :+ draft) >> published.complete(()).void.as(PhotoPublication.PublishResult.Published)

      buildManager(discover = discover, read = read, publish = publish).use: manager =>
        for
          accepted  <- manager.refresh(chapter).timeout(2.seconds)
          coalesced <- manager.refresh(chapter)
          _         <- discoveryGate.complete(())
          _         <- published.get
          partial   <- drafts.get
          _         <- secondGate.complete(())
          finished  <- awaitFinished(manager)
        yield
          assertEquals(accepted, RefreshResult.Queued)
          assertEquals(coalesced, RefreshResult.Coalesced)
          assertEquals(partial.map(_.original), Vector(location))
          assertEquals(finished.processing.state, ProcessingState.Completed)
          assertEquals(finished.processing.unsupportedCount, 2)
    }

  test("should preserve identity and caption on changed hashes or quality and skip unchanged photos"):
    val cases = Vector(
      (metadata, ThumbnailQuality.Standard, false),
      (metadata.copy(hash = "changed"), ThumbnailQuality.Standard, true),
      (metadata, ThumbnailQuality.High, true)
    )

    cases.traverse_ { case (readMetadata, quality, shouldPublish) =>
      Ref.of[IO, Vector[PhotoPublication.Draft]](Vector.empty).flatMap: drafts =>
        val publish  = (draft: PhotoPublication.Draft) => drafts.update(_ :+ draft).as(PhotoPublication.PublishResult.Published)
        val resource = buildManager(
          photos = Vector(existing),
          read = _ => PhotoFileSystem.ReadResult.Read(content, readMetadata).pure[IO],
          publish = publish,
          quality = quality
        )

        resource.use: manager =>
          for
            _      <- manager.refresh(chapter)
            _      <- awaitFinished(manager)
            actual <- drafts.get
          yield
            val expected = if shouldPublish then
              Vector(PhotoPublication.Draft(existing.id, chapter, location, readMetadata, existing.maybeCaption, thumbnail.location.some))
            else Vector.empty[PhotoPublication.Draft]
            assertEquals(actual, expected)
    }

  test("should retire only confirmed absences and preserve photos after failed enumeration"):
    val cases = Vector(
      (PhotoFileSystem.DiscoveryResult.Complete(originals = Vector.empty[OriginalPhotoLocation], unsupportedCount = 0), true),
      (PhotoFileSystem.DiscoveryResult.Failed, false)
    )

    cases.traverse_ { case (discovery, shouldRetire) =>
      Ref.of[IO, Vector[PhotoId]](Vector.empty).flatMap: retired =>
        val retire = (id: PhotoId) => retired.update(_ :+ id).as(PhotoPublication.RetireResult.Retired)

        buildManager(discover = discovery.pure[IO], photos = Vector(existing), retire = retire).use: manager =>
          for
            _      <- manager.refresh(chapter)
            _      <- awaitFinished(manager)
            actual <- retired.get
          yield assertEquals(actual, if shouldRetire then Vector(existing.id) else Vector.empty[PhotoId])
    }

  test("should expose skipped reads and failures without publishing partial photos"):
    val read      = PhotoFileSystem.ReadResult.Read(content, metadata)
    val generated = PhotoThumbnailGenerator.GenerationResult.Generated(image)
    val published = PhotoPublication.PublishResult.Published
    val failed    = PhotoPublication.PublishResult.Failed(PhotoPublication.Failure.Storage)
    val cases     = Vector(
      (PhotoFileSystem.ReadResult.Undecodable, generated, published, ProcessingState.Completed, 1),
      (PhotoFileSystem.ReadResult.Unstable, generated, published, ProcessingState.Completed, 1),
      (PhotoFileSystem.ReadResult.Failed, generated, published, ProcessingState.Failed, 0),
      (read, PhotoThumbnailGenerator.GenerationResult.Failed, published, ProcessingState.Failed, 0),
      (read, generated, failed, ProcessingState.Failed, 0)
    )

    cases.traverse_ { case (readResult, generated, publication, expectedState, expectedSkipped) =>
      buildManager(read = _ => readResult.pure[IO], generated = generated, publish = _ => publication.pure[IO]).use: manager =>
        for
          _      <- manager.refresh(chapter)
          result <- awaitFinished(manager)
        yield
          assertEquals(result.processing.state, expectedState)
          assertEquals(result.processing.skippedCount, expectedSkipped)
          assertEquals(result.processing.issues.size, 1)
    }

  test("should distinguish empty inventories, missing chapters and failed reads"):
    Vector(context, ChapterRepository.PhotoReader.FindResult.NotFound, ChapterRepository.PhotoReader.FindResult.Failed).traverse_ { chapterContext =>
      buildManager(chapterContext = chapterContext.pure[IO]).use: manager =>
        for
          inventory <- manager.listChapter(chapter)
          refreshed <- manager.refresh(chapter)
        yield chapterContext match
          case ChapterRepository.PhotoReader.FindResult.Found(_, _) =>
            val expected = InventoryResult.Found(Inventory(photos = Vector.empty[Photo], placed = Set.empty[PhotoId], Processing.idle))
            assertEquals(inventory, expected)
            assertEquals(refreshed, RefreshResult.Queued)
          case ChapterRepository.PhotoReader.FindResult.NotFound =>
            assertEquals(inventory, InventoryResult.NotFound)
            assertEquals(refreshed, RefreshResult.ChapterNotFound)
          case ChapterRepository.PhotoReader.FindResult.Failed =>
            assertEquals(inventory, InventoryResult.Failed)
            assertEquals(refreshed, RefreshResult.Failed)
    }

  test("should read authoritative placements and send chapter-addressed invalidations after registration"):
    Deferred[IO, Unit].flatMap: registered =>
      buildManager(photos = Vector(existing), placed = Set(existing.id)).use: manager =>
        val subscription = manager.subscribeToChanges(album).evalTap:
          case PhotoEvent.Subscribed          => registered.complete(()).void
          case PhotoEvent.InventoryChanged(_) => IO.unit

        subscription.take(2).compile.toVector.start.flatMap: events =>
          for
            _         <- registered.get
            _         <- manager.refresh(chapter)
            actual    <- events.joinWithNever.timeout(3.seconds)
            inventory <- manager.listChapter(chapter)
          yield
            val counts = inventory match
              case InventoryResult.Found(value) => (value.total, value.unplaced).some
              case _                            => None
            assertEquals(actual, Vector(PhotoEvent.Subscribed, PhotoEvent.InventoryChanged(chapter)))
            assertEquals(counts, (1, 0).some)

  test("should reject failed photo, placement and transaction snapshots rather than showing empty photos"):
    val unavailableTransaction = new Transaction[IO]:
      override def atomically[E, A](operation: IO[Transaction.Decision[E, A]]): IO[Transaction.Result[E, A]] = Transaction.Result.Failed.pure[IO]
    val cases = Vector(
      (PhotoRepository.ListResult.Failed, PhotoPlacementRepository.FindResult.Found(Set.empty[PhotoId]), summon[Transaction[IO]]),
      (PhotoRepository.ListResult.Listed(Vector(existing)), PhotoPlacementRepository.FindResult.Failed, summon[Transaction[IO]]),
      (PhotoRepository.ListResult.Listed(Vector(existing)), PhotoPlacementRepository.FindResult.Found(Set(existing.id)), unavailableTransaction)
    )

    cases.traverse_ { case (listed, placed, transaction) =>
      buildManager(maybeListing = listed.pure[IO].some, maybePlacements = placed.some, transaction = transaction).use: manager =>
        manager.listChapter(chapter).map(result => assertEquals(result, InventoryResult.Failed))
    }

  test("should preserve camera-local chronology, put undated photos last and break filename ties deterministically"):
    val undated   = existing.copy(original = location.copy(relative = "A.jpg"), metadata = metadata.copy(maybeCapturedLocal = None))
    val lowercase = existing.copy(original = location.copy(relative = "a.jpg"))
    val uppercase = existing.copy(original = location.copy(relative = "A.jpg"))
    val photos    = Vector(undated, lowercase, uppercase)

    buildManager(photos = photos).use: manager =>
      manager.listChapter(chapter).map:
        case InventoryResult.Found(value) =>
          assertEquals(value.photos, Vector(uppercase, lowercase, undated))
          assertEquals(value.unplaced, 3)
          assertEquals(value.processing, Processing.idle)
        case result => fail(s"Expected a chronological inventory, obtained $result")

  test("should hide superseded generation and feedback even when postcommit scheduling fails"):
    Vector(false, true).traverse_ { failScheduling =>
      val setup = (
        Ref.of[IO, Map[ChapterId, BigInt]](Map.empty),
        Deferred[IO, Unit],
        Deferred[IO, Unit],
        Ref.of[IO, Vector[PhotoPublication.Draft]](Vector.empty),
        Ref.of[IO, ChapterRepository.PhotoReader.FindResult](context)
      ).tupled

      TestControl.executeEmbed:
        setup.flatMap { case (epochs, entered, release, published, chapterContext) =>
          val generate = entered.complete(()).void >> release.get.as(PhotoThumbnailGenerator.GenerationResult.Generated(image))
          val publish  = (draft: PhotoPublication.Draft) => published.update(_ :+ draft).as(PhotoPublication.PublishResult.Published)

          buildManager(
            epochs = epochs.pure[IO],
            chapterContext = chapterContext.get,
            maybeGenerate = generate.some,
            publish = publish
          ).use: manager =>
            val reschedule = if failScheduling then
              chapterContext.set(ChapterRepository.PhotoReader.FindResult.Failed) >>
                manager.refresh(chapter).map(result => assertEquals(result, RefreshResult.Failed)) >> chapterContext.set(context)
            else IO.unit

            for
              _       <- manager.refresh(chapter)
              _       <- entered.get
              _       <- epochs.update(_.updated(chapter, BigInt(1))).timeout(2.seconds)
              _       <- epochs.update(_.updated(chapter, BigInt(2)))
              _       <- reschedule
              blocked <- manager.listChapter(chapter)
              _       <- release.complete(())
              _       <- IO.sleep(1.second)
              ended   <- manager.listChapter(chapter)
              actual  <- published.get
            yield
              val expected = InventoryResult.Found(Inventory(photos = Vector.empty[Photo], placed = Set.empty[PhotoId], Processing.idle))
              assertEquals(blocked, expected)
              assertEquals(ended, expected)
              assertEquals(actual, Vector.empty[PhotoPublication.Draft])
        }
    }

  test("should retain explicit failures for interrupted workers and rejected publication or retirement"):
    val rejected = Vector(PhotoPublication.Failure.Storage, PhotoPublication.Failure.Persistence, PhotoPublication.Failure.Cleanup).traverse_ {
      failure =>
        buildManager(
          photos = Vector(existing.copy(original = location.copy(relative = "absent.jpg"))),
          retire = _ => PhotoPublication.RetireResult.Failed(failure).pure[IO],
          publish = _ => PhotoPublication.PublishResult.Failed(failure).pure[IO]
        ).use: manager =>
          for
            _     <- manager.refresh(chapter)
            value <- awaitFinished(manager)
          yield
            assertEquals(value.processing.state, ProcessingState.Failed)
            assertEquals(value.processing.issues.size, 2)
    }
    val interrupted = buildManager(discover = IO.raiseError[PhotoFileSystem.DiscoveryResult](new IllegalStateException("Disconnected source"))).use:
      manager =>
        for
          _     <- manager.refresh(chapter)
          value <- awaitFinished(manager)
        yield assertEquals(value.processing.issues, Vector(Issue.Unexpected))

    rejected >> interrupted

  test("should retain journal-owned supersession outcomes without declaring a failed import"):
    buildManager(
      photos = Vector(existing.copy(original = location.copy(relative = "absent.jpg"))),
      retire = _ => PhotoPublication.RetireResult.Superseded.pure[IO],
      publish = _ => PhotoPublication.PublishResult.Superseded.pure[IO]
    ).use: manager =>
      for
        _     <- manager.refresh(chapter)
        value <- awaitFinished(manager)
      yield assertEquals(value.processing.state, ProcessingState.Completed)

  test("should report a failed background repository read and a closed scheduling resource"):
    val setup = (Ref.of[IO, Boolean](true), Deferred[IO, Unit]).tupled

    setup.flatMap { case (firstRead, failed) =>
      val listing = firstRead.getAndSet(false).flatMap: first =>
        if first then failed.complete(()).as(PhotoRepository.ListResult.Failed) else PhotoRepository.ListResult.Listed(Vector.empty).pure[IO]
      val failedRead = buildManager(maybeListing = listing.some).use: manager =>
        for
          _     <- manager.refresh(chapter)
          _     <- failed.get
          value <- awaitFinished(manager)
        yield assertEquals(value.processing.issues, Vector(Issue.Persistence))
      val closed = buildManager().allocated.flatMap { case (manager, release) =>
        release >> manager.refresh(chapter).map(result => assertEquals(result, RefreshResult.Failed))
      }

      failedRead >> closed
    }

  test("should accept another Refresh after completion"):
    buildManager().use: manager =>
      for
        first <- manager.refresh(chapter)
        _     <- awaitFinished(manager)
        next  <- manager.refresh(chapter)
        _     <- awaitFinished(manager)
      yield assertEquals((first, next), (RefreshResult.Queued, RefreshResult.Queued))

  test("should coalesce slow subscribers by chapter"):
    val setup = (Deferred[IO, Unit], Deferred[IO, Unit]).tupled

    setup.flatMap { case (registered, resume) =>
      buildManager().use: manager =>
        val subscription = manager.subscribeToChanges(album).evalTap:
          case PhotoEvent.Subscribed          => registered.complete(()).void >> resume.get
          case PhotoEvent.InventoryChanged(_) => IO.unit
        val other = ChapterId(new UUID(0L, 9L))

        subscription.take(3).compile.toVector.start.flatMap: events =>
          for
            _      <- registered.get
            _      <- manager.refresh(chapter)
            _      <- manager.refresh(other)
            _      <- awaitFinished(manager)
            _      <- resume.complete(())
            actual <- events.joinWithNever.timeout(3.seconds)
          yield assertEquals(actual, Vector(PhotoEvent.Subscribed, PhotoEvent.InventoryChanged(chapter), PhotoEvent.InventoryChanged(other)))
    }

  test("should remain quiescent while idle and deliver again after cancellation and during a drain"):
    val setup = (Deferred[IO, Unit], Deferred[IO, Unit], Deferred[IO, Unit], Ref.of[IO, Boolean](true)).tupled

    TestControl.executeEmbed:
      setup.flatMap { case (registered, delivered, resume, first) =>
        buildManager().use: manager =>
          val idle         = manager.subscribeToChanges(album).interruptAfter(1.second).compile.toVector
          val subscription = manager.subscribeToChanges(album).evalTap:
            case PhotoEvent.Subscribed          => registered.complete(()).void
            case PhotoEvent.InventoryChanged(_) => first.getAndSet(false).flatMap: initial =>
                if initial then delivered.complete(()).void >> resume.get else IO.unit

          for
            idleEvents <- idle
            events     <- subscription.take(3).compile.toVector.start
            _          <- registered.get
            _          <- manager.refresh(chapter)
            _          <- delivered.get
            _          <- awaitFinished(manager)
            _          <- manager.refresh(chapter)
            _          <- awaitFinished(manager)
            _          <- resume.complete(())
            actual     <- events.joinWithNever
          yield
            assertEquals(idleEvents, Vector(PhotoEvent.Subscribed))
            assertEquals(actual, Vector(PhotoEvent.Subscribed, PhotoEvent.InventoryChanged(chapter), PhotoEvent.InventoryChanged(chapter)))
      }

  test("should ignore an old worker's completion after replacement work has finished"):
    val setup = (Ref.of[IO, Map[ChapterId, BigInt]](Map.empty), Deferred[IO, Unit], Deferred[IO, Unit], Ref.of[IO, Boolean](true)).tupled

    TestControl.executeEmbed:
      setup.flatMap { case (epochs, started, resume, first) =>
        val discover = first.getAndSet(false).flatMap: old =>
          if old then started.complete(()).void >> resume.get.as(PhotoFileSystem.DiscoveryResult.Failed)
          else PhotoFileSystem.DiscoveryResult.Complete(originals = Vector.empty[OriginalPhotoLocation], unsupportedCount = 0).pure[IO]

        buildManager(epochs = epochs.pure[IO], discover = discover).use: manager =>
          for
            _     <- manager.refresh(chapter)
            _     <- started.get
            _     <- epochs.update(_.updated(chapter, BigInt(1)))
            _     <- manager.refresh(chapter)
            _     <- awaitFinished(manager)
            _     <- resume.complete(())
            _     <- IO.sleep(1.second)
            value <- manager.listChapter(chapter)
          yield
            val expected = InventoryResult.Found(Inventory(
              photos = Vector.empty[Photo],
              placed = Set.empty,
              Processing(ProcessingState.Completed, unsupportedCount = 0, issues = Vector.empty[Issue])
            ))
            assertEquals(value, expected)
      }

  private def awaitFinished(manager: PhotoManager[IO]) =
    fs2.Stream.repeatEval(manager.listChapter(chapter) <* IO.cede).collect {
      case InventoryResult.Found(value) if Set(ProcessingState.Completed, ProcessingState.Failed).contains(value.processing.state) => value
    }.head.compile.lastOrError.timeout(5.seconds)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      chapterContext: IO[ChapterRepository.PhotoReader.FindResult] = context.pure[IO],
      discover: IO[PhotoFileSystem.DiscoveryResult] = PhotoFileSystem.DiscoveryResult.Complete(Vector(location), unsupportedCount = 0).pure[IO],
      read: OriginalPhotoLocation => IO[PhotoFileSystem.ReadResult] = _ => PhotoFileSystem.ReadResult.Read(content, metadata).pure[IO],
      photos: Vector[Photo] = Vector.empty,
      placed: Set[PhotoId] = Set.empty,
      generated: PhotoThumbnailGenerator.GenerationResult = PhotoThumbnailGenerator.GenerationResult.Generated(image),
      publish: PhotoPublication.Draft => IO[PhotoPublication.PublishResult] = _ => PhotoPublication.PublishResult.Published.pure[IO],
      retire: PhotoId => IO[PhotoPublication.RetireResult] = _ => PhotoPublication.RetireResult.Retired.pure[IO],
      quality: ThumbnailQuality = ThumbnailQuality.Standard,
      maybeListing: Option[IO[PhotoRepository.ListResult]] = None,
      maybePlacements: Option[PhotoPlacementRepository.FindResult] = None,
      transaction: Transaction[IO] = summon[Transaction[IO]],
      epochs: IO[Ref[IO, Map[ChapterId, BigInt]]] = Ref.of[IO, Map[ChapterId, BigInt]](Map.empty),
      maybeGenerate: Option[IO[PhotoThumbnailGenerator.GenerationResult]] = None
  ) =
    given IdGenerator[IO] with
      private val next                  = Ref.unsafe[IO, Long](10)
      override def generateId: IO[UUID] = next.getAndUpdate(_ + 1L).map(index => new UUID(0L, index))
    val publishDraft = publish
    val retirePhoto  = retire
    val chapters     = new ChapterRepository.PhotoReader[IO]:
      override def findPhotoContext(id: ChapterId) = chapterContext
    val files = new PhotoFileSystem[IO]:
      override def discoverOriginals(source: PhotoSource)        = discover
      override def readOriginal(original: OriginalPhotoLocation) = read(original)
    val repository = new PhotoRepository[IO]:
      override def listChapter(id: ChapterId) = maybeListing.getOrElse(PhotoRepository.ListResult.Listed(photos).pure[IO])
    val placements = new PhotoPlacementRepository[IO]:
      override def findPlacedPhotos(id: ChapterId) = maybePlacements.getOrElse(PhotoPlacementRepository.FindResult.Found(placed)).pure[IO]
    val generator = new PhotoThumbnailGenerator[IO]:
      override def generate(original: PhotoContent) = maybeGenerate.getOrElse(generated.pure[IO])
    val publication = new PhotoPublication[IO]:
      override def publish(draft: PhotoPublication.Draft, thumbnail: EncodedThumbnail, epoch: BigInt)      = publishDraft(draft)
      override def retire(chapter: ChapterId, photo: PhotoId, thumbnail: ThumbnailLocation, epoch: BigInt) = retirePhoto(photo)

    Mutex[IO].toResource.flatMap: mutex =>
      given Mutex[IO]       = mutex
      given Transaction[IO] = transaction
      epochs.toResource.flatMap: sharedEpochs =>
        PhotoManager.make[IO](chapters, files, repository, placements, generator, publication, sharedEpochs, quality)
  // scalafix:on DisableSyntax.defaultArgs
