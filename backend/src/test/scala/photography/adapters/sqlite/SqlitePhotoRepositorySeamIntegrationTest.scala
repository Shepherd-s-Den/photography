package photography.adapters.sqlite

import cats.Eq
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.ConnectionIO
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.adapters.filesystem.FileThumbnailStorage
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.capabilities.{IdGenerator, Transaction}
import photography.domain.album.*
import photography.ports.PhotoRepository.*
import photography.ports.{PhotoStorage, ThumbnailStorage}
import photography.syntax.SyncSyntax.*
import photography.usecases.{PhotoManager, PhotoThumbnailGenerator, PhotoWriteRecovery}
import java.nio.file.{Files, Path}
import java.time.LocalDateTime
import java.util.UUID

final class SqlitePhotoRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private given IdGenerator[IO] with
    override def generateId: IO[UUID] = new UUID(0L, 8L).pure[IO]
  private val chapter  = ChapterId(new UUID(0L, 1L))
  private val photoId  = PhotoId(new UUID(0L, 2L))
  private val writeId  = PhotoWriteIntentId(new UUID(0L, 3L))
  private val location = ThumbnailLocation(new UUID(0L, 4L))
  private val metadata =
    PhotoMetadata(hash = "hash", maybeCapturedLocal = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456789).some, PhotoDimensions(width = 10, height = 20))
  private val thumbnail = Thumbnail(location, version = "version", ThumbnailDimensions(width = 5, height = 10), ThumbnailQuality.Standard, bytes = 1)
  private val photo     =
    Photo(photoId, chapter, OriginalPhotoLocation(source = "/photos", filename = "one.jpg"), metadata, thumbnail, maybeCaption = "caption".some)
  private val publication = PhotoWriteIntent.Publication(writeId, chapter, photoId, location, maybePrevious = none[ThumbnailLocation])
  private val retirement  = PhotoWriteIntent.Retirement(writeId, chapter, photoId, location)
  private val content     = PhotoContent(Vector(1.toByte).refineEither[MinLength[1]].fold(message => fail(message), identity))
  private val imageBytes  = Vector(1.toByte).refineEither[ThumbnailBytesConstraint].fold(message => fail(message), identity)
  private val image       = EncodedThumbnail(imageBytes, thumbnail.dimensions, thumbnail.quality)

  test("should retain complete photos and write evidence across database reopening"):
    buildDatabase.use: path =>
      val repository  = SqlitePhotoRepository.make
      val replacement = photo.copy(metadata = metadata.copy(hash = "edited", maybeCapturedLocal = none[LocalDateTime]))

      val persist = buildTransactor(path).use: xa =>
        (seed >> repository.recordWrite(publication) >> repository.save(photo)).transact(xa)

      persist >> buildTransactor(path).use: xa =>
        for
          pending <- repository.listUnfinishedWrites.transact(xa)
          listed  <- repository.listChapter(chapter).transact(xa)
          _       <- repository.save(replacement).transact(xa)
          changed <- repository.listChapter(chapter).transact(xa)
          _       <- repository.completeWrite(writeId).transact(xa)
          _       <- repository.completeWrite(writeId).transact(xa)
          cleared <- repository.listUnfinishedWrites.transact(xa)
        yield
          assertEquals(pending, ListUnfinishedWritesResult.Listed(intents = Vector(publication)))
          assertEquals(listed, ListResult.Listed(photos = Vector(photo)))
          assertEquals(changed, ListResult.Listed(photos = Vector(replacement)))
          assertEquals(cleared, ListUnfinishedWritesResult.Listed(intents = Vector.empty[PhotoWriteIntent]))

  test("should roll back retirement evidence with removal and retain committed evidence after chapter deletion"):
    buildDatabase.use: path =>
      buildTransactor(path).use: xa =>
        val repository = SqlitePhotoRepository.make
        val removal    = repository.recordWrite(retirement) >> repository.remove(photoId)
        val rejected   = removal >> new IllegalStateException("Reject transaction").raiseError[ConnectionIO, Unit]

        for
          _          <- (seed >> repository.save(photo)).transact(xa)
          failed     <- rejected.transact(xa).attempt
          retained   <- repository.listChapter(chapter).transact(xa)
          rolledBack <- repository.listUnfinishedWrites.transact(xa)
          _          <- removal.transact(xa)
          empty      <- repository.listChapter(chapter).transact(xa)
          _          <- sql"DELETE FROM chapter WHERE id = ${chapter.value}".update.run.transact(xa)
          pending    <- repository.listUnfinishedWrites.transact(xa)
          absent     <- repository.remove(photoId).transact(xa)
        yield
          assert(failed.isLeft)
          assertEquals(retained, ListResult.Listed(photos = Vector(photo)))
          assertEquals(rolledBack, ListUnfinishedWritesResult.Listed(intents = Vector.empty[PhotoWriteIntent]))
          assertEquals(empty, ListResult.Listed(photos = Vector.empty[Photo]))
          assertEquals(pending, ListUnfinishedWritesResult.Listed(intents = Vector(retirement)))
          assertEquals(absent, WriteResult.Applied)

  test("should round-trip every thumbnail quality and reject moving a photo to another chapter"):
    buildDatabase.use: path =>
      buildTransactor(path).use: xa =>
        val repository     = SqlitePhotoRepository.make
        val otherChapter   = ChapterId(new UUID(0L, 9L))
        val insertChapter  = sql"INSERT INTO chapter VALUES (${otherChapter.value}, 'a', 'd', '', 0, 1, 1, '/other')"
        val checkQualities = ThumbnailQuality.values.toVector.traverse_ { quality =>
          val complete = photo.copy(thumbnail = thumbnail.copy(quality = quality))
          (repository.save(complete) >> repository.listChapter(chapter)).transact(xa)
            .map(actual => assertEquals(actual, ListResult.Listed(photos = Vector(complete))))
        }

        for
          _        <- seed.transact(xa)
          _        <- checkQualities
          _        <- insertChapter.update.run.transact(xa)
          rejected <- repository.save(photo.copy(chapter = otherChapter)).transact(xa)
          empty    <- repository.listChapter(otherChapter).transact(xa)
        yield
          assertEquals(rejected, WriteResult.Failed)
          assertEquals(empty, ListResult.Listed(photos = Vector.empty[Photo]))

  test("should reject malformed persisted photo representations rather than return invalid values"):
    Vector(
      sql"UPDATE photo SET source = '../escape.jpg'",
      sql"UPDATE photo SET captured_local = '2026-02-30T00:00:00.000000000'",
      sql"UPDATE photo SET thumbnail_quality = 'low', thumbnail_width = 1025",
      sql"UPDATE photo SET thumbnail_quality = 'standard', thumbnail_height = 1537"
    )
      .traverse_ { corrupt =>
        buildDatabase.use: path =>
          buildTransactor(path).use: xa =>
            val repository = SqlitePhotoRepository.make

            for
              _      <- (seed >> repository.save(photo) >> corrupt.update.run).transact(xa)
              result <- repository.listChapter(chapter).transact(xa).attempt
            yield assert(result.isLeft)
      }

  test("should reject malformed persisted write evidence"):
    Vector(
      sql"UPDATE photo_write_intent SET kind = 'unknown'",
      sql"UPDATE photo_write_intent SET kind = 'retirement', previous = ${location.value}"
    ).traverse_ { corruption =>
      buildDatabase.use: path =>
        buildTransactor(path).use: xa =>
          val repository = SqlitePhotoRepository.make
          val corrupt    = sql"PRAGMA ignore_check_constraints = ON".update.run >> corruption.update.run >>
            sql"PRAGMA ignore_check_constraints = OFF".update.run

          for
            _        <- (seed >> repository.recordWrite(publication) >> corrupt).transact(xa)
            rejected <- repository.listUnfinishedWrites.transact(xa).attempt
          yield rejected match
            case Left(error: IllegalArgumentException) => assertEquals(error.getMessage, "Invalid photo write evidence")
            case result                                => fail(s"Expected invalid write evidence rejection, obtained $result")
    }

  test("should resolve live uncertain commits or recover retained evidence from reopened SQLite and managed files"):
    Vector(false, true).traverse_ { retiring =>
      Vector(false, true).traverse_ { committed =>
        Finalization.values.toVector.traverse_ { finalization =>
          (buildDatabase, buildDirectory).tupled.use { case (path, root) =>
            val repository       = SqlitePhotoRepository.make
            val storage          = FileThumbnailStorage.make[IO](root)
            val fresh            = ThumbnailLocation(new UUID(0L, 8L))
            val intentId         = PhotoWriteIntentId(new UUID(0L, 8L))
            val expectedLocation = if retiring || !committed then location else fresh
            val expectedPhotos   = if retiring && committed then Vector.empty[PhotoId] else Vector(photo.id)
            val expectedPending  = if (retiring && !committed) || finalization === Finalization.Complete then 0 else 1
            val expectedIntent   = PhotoWriteIntent.Publication(intentId, chapter, photoId, fresh, maybePrevious = location.some)
            val expectedEvidence = ListUnfinishedWritesResult.Listed(intents = Vector(expectedIntent))
            val error            = new IllegalStateException("Interrupted commit acknowledgement")
            val generator        = buildGenerator

            val interrupt = buildTransactor(path).use: xa =>
              val storageBoundary = new PhotoStorage[IO]:
                override def discoverOriginals(source: PhotoSource): IO[PhotoStorage.DiscoveryResult] =
                  val originals = if retiring then Vector.empty[OriginalPhotoLocation] else Vector(photo.original)
                  PhotoStorage.DiscoveryResult.Complete(originals = originals, unsupportedCount = 0).pure[IO]
                override def readOriginal(original: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] =
                  PhotoStorage.ReadResult.Read(content, metadata.copy(hash = "changed")).pure[IO]
                override def storeThumbnail(target: ThumbnailLocation, value: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
                  repository.listUnfinishedWrites.transact(xa).flatMap: pending =>
                    IO(assertEquals(pending, expectedEvidence)) >> storage.storeThumbnail(target, value)
                override def removeThumbnail(target: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
                  if finalization === Finalization.CleanupFailed then ThumbnailStorage.RemoveResult.Failed.pure[IO]
                  else storage.removeThumbnail(target)
              val calls       = Ref.unsafe[IO, Int](0)
              val resolutions = Ref.unsafe[IO, Int](0)
              given Transaction[ConnectionIO, IO] with
                override def run[A](program: ConnectionIO[A]): IO[A] =
                  calls.updateAndGet(_ + 1).flatMap: count =>
                    val publicationOrRemoval = count === (if retiring then 2 else 3)
                    if !publicationOrRemoval then program.transact(xa)
                    else if committed then program.transact(xa) >> error.raiseError[IO, A]
                    else (program >> error.raiseError[ConnectionIO, A]).transact(xa)
                override def resolve[A](program: ConnectionIO[A]): IO[A] =
                  resolutions.updateAndGet(_ + 1).flatMap: count =>
                    val fails = finalization === Finalization.Unreadable || (finalization === Finalization.CompletionFailed && count === 2)
                    if fails then error.raiseError[IO, A] else program.transact(xa)

              for
                _       <- (seed >> repository.save(photo)).transact(xa)
                _       <- storage.storeThumbnail(location, image)
                _       <- Files.write(root.resolve("unrelated.jpg"), Array(7.toByte)).blocking[IO]
                result  <- PhotoManager.make(storageBoundary, repository, generator).refresh(chapter, photo.original.source)
                pending <- repository.listUnfinishedWrites.transact(xa)
                before  <- Files.exists(root.resolve(s"${location.value}.jpg")).blocking[IO]
              yield
                assert(result.issues.contains(PhotoManager.Issue.Persistence))
                assertEquals(before, !committed || finalization === Finalization.Unreadable || finalization === Finalization.CleanupFailed)
                pending match
                  case listed: ListUnfinishedWritesResult.Listed => assertEquals(listed.intents.size, expectedPending)
                  case ListUnfinishedWritesResult.Failed         => fail("Expected persisted evidence")

            interrupt >> buildTransactor(path).use: xa =>
              given Transaction[ConnectionIO, IO] with
                override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
                override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
              val recovery = PhotoWriteRecovery.make(repository, storage)

              for
                recovered <- recovery.recover
                repeated  <- recovery.recover
                listed    <- repository.listChapter(chapter).transact(xa)
                pending   <- repository.listUnfinishedWrites.transact(xa)
                oldExists <- Files.exists(root.resolve(s"${location.value}.jpg")).blocking[IO]
                newExists <- Files.exists(root.resolve(s"${fresh.value}.jpg")).blocking[IO]
                unrelated <- Files.readAllBytes(root.resolve("unrelated.jpg")).blocking[IO]
              yield
                assertEquals(recovered, PhotoWriteRecovery.RecoveryResult.Recovered)
                assertEquals(repeated, PhotoWriteRecovery.RecoveryResult.Recovered)
                listed match
                  case current: ListResult.Listed =>
                    assertEquals(current.photos.map(_.id), expectedPhotos)
                    assertEquals(current.photos.map(_.thumbnail.location), expectedPhotos.map(_ => expectedLocation))
                  case ListResult.Failed => fail("Expected complete photos")
                assertEquals(pending, ListUnfinishedWritesResult.Listed(intents = Vector.empty[PhotoWriteIntent]))
                assertEquals(oldExists, !committed)
                assertEquals(newExists, !retiring && committed)
                assertEquals(unrelated.toVector, Vector(7.toByte))
          }
        }
      }
    }

  test("should recover a cancelled live storage attempt without losing the previous photo or unrelated files"):
    (buildDatabase, buildDirectory).tupled.use { case (path, root) =>
      val repository      = SqlitePhotoRepository.make
      val storage         = FileThumbnailStorage.make[IO](root)
      val fresh           = ThumbnailLocation(new UUID(0L, 8L))
      val intent          = PhotoWriteIntent.Publication(PhotoWriteIntentId(new UUID(0L, 8L)), chapter, photoId, fresh, maybePrevious = location.some)
      val expectedPending = ListUnfinishedWritesResult.Listed(intents = Vector(intent))
      val generator       = buildGenerator
      val cancel          = buildTransactor(path).use: xa =>
        given Transaction[ConnectionIO, IO] with
          override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
          override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
        val source = new PhotoStorage[IO]:
          override def discoverOriginals(value: PhotoSource): IO[PhotoStorage.DiscoveryResult] =
            PhotoStorage.DiscoveryResult.Complete(originals = Vector(photo.original), unsupportedCount = 0).pure[IO]
          override def readOriginal(original: OriginalPhotoLocation): IO[PhotoStorage.ReadResult] =
            PhotoStorage.ReadResult.Read(content, metadata.copy(hash = "changed")).pure[IO]
          override def storeThumbnail(target: ThumbnailLocation, value: EncodedThumbnail): IO[ThumbnailStorage.StoreResult] =
            val partial = root.resolve(s"${target.value}.tmp")
            storage.storeThumbnail(target, value) >> Files.write(partial, Array(2.toByte)).blocking[IO] >>
              IO.canceled.as(ThumbnailStorage.StoreResult.Failed)
          override def removeThumbnail(target: ThumbnailLocation): IO[ThumbnailStorage.RemoveResult] =
            ThumbnailStorage.RemoveResult.Failed.pure[IO]

        for
          _       <- (seed >> repository.save(photo)).transact(xa)
          _       <- storage.storeThumbnail(location, image)
          _       <- Files.write(root.resolve("unrelated.jpg"), Array(7.toByte)).blocking[IO]
          worker  <- PhotoManager.make(source, repository, generator).refresh(chapter, photo.original.source).start
          outcome <- worker.join
          pending <- repository.listUnfinishedWrites.transact(xa)
          listed  <- repository.listChapter(chapter).transact(xa)
        yield
          assert(outcome.isCanceled)
          assertEquals(pending, expectedPending)
          assertEquals(listed, ListResult.Listed(photos = Vector(photo)))

      cancel >> buildTransactor(path).use: xa =>
        given Transaction[ConnectionIO, IO] with
          override def run[A](program: ConnectionIO[A]): IO[A]     = program.transact(xa)
          override def resolve[A](program: ConnectionIO[A]): IO[A] = program.transact(xa)
        val recovery = PhotoWriteRecovery.make(repository, storage)

        for
          result        <- recovery.recover
          pending       <- repository.listUnfinishedWrites.transact(xa)
          listed        <- repository.listChapter(chapter).transact(xa)
          finalExists   <- Files.exists(root.resolve(s"${fresh.value}.jpg")).blocking[IO]
          partialExists <- Files.exists(root.resolve(s"${fresh.value}.tmp")).blocking[IO]
          oldBytes      <- Files.readAllBytes(root.resolve(s"${location.value}.jpg")).blocking[IO]
          unrelated     <- Files.readAllBytes(root.resolve("unrelated.jpg")).blocking[IO]
        yield
          assertEquals(result, PhotoWriteRecovery.RecoveryResult.Recovered)
          assertEquals(pending, ListUnfinishedWritesResult.Listed(intents = Vector.empty[PhotoWriteIntent]))
          assertEquals(listed, ListResult.Listed(photos = Vector(photo)))
          assertEquals((finalExists, partialExists), (false, false))
          assertEquals(oldBytes.toVector, imageBytes: Vector[Byte])
          assertEquals(unrelated.toVector, Vector(7.toByte))
    }

  private enum Finalization:
    case Unreadable, CleanupFailed, CompletionFailed, Complete
  private given Eq[Finalization] = Eq.fromUniversalEquals

  private def buildGenerator =
    new PhotoThumbnailGenerator[IO]:
      override def quality: ThumbnailQuality                                                   = image.quality
      override def generate(bytes: PhotoContent): IO[PhotoThumbnailGenerator.GenerationResult] =
        PhotoThumbnailGenerator.GenerationResult.Generated(image).pure[IO]

  private def buildDirectory =
    Resource.make(Files.createTempDirectory(Path.of("target"), "live-write-").toAbsolutePath.blocking[IO]): root =>
      Vector(s"${location.value}.jpg", s"${new UUID(0L, 8L)}.jpg", s"${new UUID(0L, 8L)}.tmp", "unrelated.jpg").traverse_ { name =>
        Files.deleteIfExists(root.resolve(name)).blocking[IO].void
      } >> Files.deleteIfExists(root).blocking[IO].void

  private def seed =
    sql"INSERT INTO album VALUES ('a', 2026, 'Album', '', 0)".update.run >>
      sql"INSERT INTO destination VALUES ('d', 'Trip')".update.run >>
      sql"INSERT INTO chapter VALUES (${chapter.value}, 'a', 'd', '', 0, 1, 0, '/photos')".update.run.void

  private def buildDatabase =
    Resource.make(Files.createTempFile(Path.of("target"), "photo-journal-", ".sqlite").blocking[IO])(path =>
      Files.deleteIfExists(path).blocking[IO].void
    ).evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void)

  private def buildTransactor(path: Path) =
    SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor
