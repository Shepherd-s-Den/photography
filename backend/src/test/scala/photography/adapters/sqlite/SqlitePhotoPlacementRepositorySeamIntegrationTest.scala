package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.app.config.SqliteConfig
import photography.domain.album.{ChapterId, PhotoId}
import photography.ports.PhotoPlacementRepository.FindResult
import photography.syntax.SyncSyntax.*
import java.nio.file.{Files, Path}
import java.util.UUID

final class SqlitePhotoPlacementRepositorySeamIntegrationTest extends CatsEffectSuite:
  test("should read authoritative chapter-owned spread and cover placements, including empty inventories and failures"):
    val chapter  = ChapterId(new UUID(0L, 1L))
    val other    = ChapterId(new UUID(0L, 2L))
    val spread   = PhotoId(new UUID(0L, 3L))
    val cover    = PhotoId(new UUID(0L, 4L))
    val foreign  = PhotoId(new UUID(0L, 5L))
    val unplaced = ChapterId(new UUID(0L, 6L))
    val database = Resource.make(Files.createTempFile(Path.of("target"), "placements-", ".sqlite").blocking[IO]): path =>
      Files.deleteIfExists(path).blocking[IO].void

    database.evalTap(path => FlywayMigrator.make[IO](path).use(_.migrate).void).use: path =>
      SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 1, maxSessions = 1)).buildTransactor.use: xa =>
        val repository = SqlitePhotoPlacementRepository.make
        val seed       =
          sql"INSERT INTO album VALUES ('a', 2026, 'Album', '', 0)".update.run >>
            sql"INSERT INTO destination VALUES ('d', 'Trip')".update.run >>
            sql"INSERT INTO chapter VALUES (${chapter.value}, 'a', 'd', '', 0, 1, 0, '/photos')".update.run >>
            sql"INSERT INTO chapter VALUES (${other.value}, 'a', 'd', '', 0, 1, 1, '/other')".update.run >>
            sql"INSERT INTO chapter VALUES (${unplaced.value}, 'a', 'd', '', 0, 1, 2, '/empty')".update.run >>
            sql"INSERT INTO cover VALUES ('a', 'front')".update.run >>
            sql"INSERT INTO cover_slot VALUES ('front', 'a', 'front'), ('back', 'a', 'back')".update.run >>
            sql"INSERT INTO spread_template VALUES ('template', 'Test', 'photos_only')".update.run >>
            sql"INSERT INTO spread VALUES ('spread', 'template', 'photos_only', NULL, ${chapter.value}, 1)".update.run >>
            sql"INSERT INTO spread_photo_slot VALUES ('slot', 'spread', 0, 0, 10, 10)".update.run >>
            Vector((spread, chapter), (cover, chapter), (foreign, other)).traverse_ { case (photo, owner) =>
              sql"""INSERT INTO photo
                (id, chapter_id, source, source_hash, width, height, thumbnail, thumbnail_version,
                 thumbnail_width, thumbnail_height, thumbnail_quality, thumbnail_bytes)
                VALUES (${photo.value}, ${owner.value}, ${photo.value.toString}, 'hash', 1, 1, 'key', 'version', 1, 1, 'low', 1)""".update.run.void
            } >>
            sql"""INSERT INTO placement VALUES
              ('spread-placement', ${spread.value}, 'spread', 'slot', NULL, 0, 0, 0, 1, 'top_left'),
              ('cover-placement', ${cover.value}, 'cover', NULL, 'front', 0, 0, 0, 1, NULL),
              ('other-placement', ${foreign.value}, 'cover', NULL, 'back', 0, 0, 0, 1, NULL)""".update.run

        for
          _      <- seed.transact(xa)
          placed <- repository.findPlacedPhotos(chapter).transact(xa)
          scoped <- repository.findPlacedPhotos(other).transact(xa)
          empty  <- repository.findPlacedPhotos(unplaced).transact(xa)
          failed <- (sql"DROP TABLE placement".update.run >> repository.findPlacedPhotos(chapter)).transact(xa).attempt
        yield
          assertEquals(placed, FindResult.Found(photos = Set(spread, cover)))
          assertEquals(scoped, FindResult.Found(photos = Set(foreign)))
          assertEquals(empty, FindResult.Found(photos = Set.empty[PhotoId]))
          assert(failed.isLeft)
