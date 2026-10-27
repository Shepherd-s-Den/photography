package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*
import org.typelevel.doobie.ConnectionIO
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.domain.album.*
import photography.ports.PhotoRepository.*
import java.nio.file.{Files, Path}
import java.time.LocalDateTime
import java.util.UUID

final class SqlitePhotoRepositorySeamIntegrationTest extends CatsEffectSuite:
  private val chapter  = ChapterId(new UUID(0L, 1L))
  private val photoId  = PhotoId(new UUID(0L, 2L))
  private val writeId  = PhotoWriteIntentId(new UUID(0L, 3L))
  private val location = ThumbnailLocation(new UUID(0L, 4L))
  private val metadata =
    PhotoMetadata(hash = "hash", maybeCapturedLocal = LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456789).some, PhotoDimensions(width = 10, height = 20))
  private val thumbnail = Thumbnail(location, version = "version", ThumbnailDimensions(width = 5, height = 10), ThumbnailQuality.Standard, bytes = 1)
  private val photo     =
    Photo(photoId, chapter, OriginalPhotoLocation(source = "/photos", filename = "one.jpg"), metadata, thumbnail, maybeCaption = "caption".some)
  private val publication = PhotoWriteIntent.Publication(writeId, chapter, photoId, location, maybePrevious = None)
  private val retirement  = PhotoWriteIntent.Retirement(writeId, chapter, photoId, location)

  test("should retain complete photos and write evidence across database reopening"):
    buildDatabase.use: path =>
      val repository  = SqlitePhotoRepository.make
      val replacement = photo.copy(metadata = metadata.copy(hash = "edited", maybeCapturedLocal = None))

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
    Vector(sql"UPDATE photo SET source = '../escape.jpg'", sql"UPDATE photo SET captured_local = '2026-02-30T00:00:00.000000000'")
      .traverse_ { corrupt =>
        buildDatabase.use: path =>
          buildTransactor(path).use: xa =>
            val repository = SqlitePhotoRepository.make

            for
              _      <- (seed >> repository.save(photo) >> corrupt.update.run).transact(xa)
              result <- repository.listChapter(chapter).transact(xa).attempt
            yield assert(result.isLeft)
      }

  private def seed =
    sql"INSERT INTO album VALUES ('a', 2026, 'Album', '', 0)".update.run >>
      sql"INSERT INTO destination VALUES ('d', 'Trip')".update.run >>
      sql"INSERT INTO chapter VALUES (${chapter.value}, 'a', 'd', '', 0, 1, 0, '/photos')".update.run.void

  private def buildDatabase =
    Resource.make(IO.blocking(Files.createTempFile(Path.of("target"), "photo-journal-", ".sqlite")))(path =>
      IO.blocking(Files.deleteIfExists(path)).void
    ).evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void)

  private def buildTransactor(path: Path) =
    SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor
