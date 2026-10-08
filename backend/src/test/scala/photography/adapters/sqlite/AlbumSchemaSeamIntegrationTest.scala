package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.sqlite.SQLiteDataSource
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import photography.app.SqliteConfig

import java.nio.file.Files

final class AlbumSchemaSeamIntegrationTest extends CatsEffectSuite:

  test("should seed one text-only AlbumStart template and add created_at_ms"):
    migratedDatabase.use: transactor =>
      for
        templateCount <- sql"SELECT COUNT(*) FROM spread_template WHERE kind = 'album_start'"
          .query[Int]
          .unique
          .transact(transactor)
        textSlotCount <- sql"""
            SELECT COUNT(*)
            FROM spread_template_slot slot
            JOIN spread_template template ON template.id = slot.template_id
            WHERE template.kind = 'album_start' AND slot.kind = 'text'
          """
          .query[Int]
          .unique
          .transact(transactor)
        photoSlotCount <- sql"""
            SELECT COUNT(*)
            FROM spread_template_slot slot
            JOIN spread_template template ON template.id = slot.template_id
            WHERE template.kind = 'album_start' AND slot.kind = 'photo'
          """
          .query[Int]
          .unique
          .transact(transactor)
        createdAtColumnCount <- sql"""
            SELECT COUNT(*) FROM pragma_table_info('album') WHERE name = 'created_at_ms'
          """
          .query[Int]
          .unique
          .transact(transactor)
      yield
        assertEquals(templateCount, 1)
        assertEquals(textSlotCount, 1)
        assertEquals(photoSlotCount, 0)
        assertEquals(createdAtColumnCount, 1)

  test("should preserve V1 albums and backfill their creation time during upgrade"):
    v1Database.use: (path, transactor) =>
      val legacyAlbumId = "00000000-0000-4000-8000-000000000099"

      for
        _ <- IO.blocking(migrateToV1(path))
        _ <- sql"""
            INSERT INTO album (id, title, synopsis)
            VALUES ($legacyAlbumId, 'Legacy album', '')
          """.update.run.transact(transactor)
        _        <- FlywayMigrator.make[IO](path).use(_.migrate)
        albumRow <- sql"""
            SELECT title, created_at_ms FROM album WHERE id = $legacyAlbumId
          """
          .query[(String, Long)]
          .unique
          .transact(transactor)
        seededTemplateCount <- sql"SELECT COUNT(*) FROM spread_template WHERE kind = 'album_start'"
          .query[Int]
          .unique
          .transact(transactor)
      yield
        assertEquals(albumRow._1, "Legacy album")
        assert(albumRow._2 > 0)
        assertEquals(seededTemplateCount, 1)

  private def migrateToV1(path: java.nio.file.Path): Unit =
    val dataSource = new SQLiteDataSource()
    dataSource.setUrl(s"jdbc:sqlite:${path.toAbsolutePath.normalize()}")
    val _ = Flyway
      .configure()
      .dataSource(dataSource)
      .locations("classpath:db")
      .table("flyway")
      .target(MigrationVersion.fromVersion("1"))
      .load()
      .migrate()

  private def v1Database: Resource[IO, (java.nio.file.Path, Transactor[IO])] =
    for
      path <- Resource.make(
        IO.blocking(Files.createTempFile("photography-album-v1", ".sqlite"))
      )(path => IO.blocking(Files.deleteIfExists(path)).void)
      transactor <- SqliteConnection.make[IO](SqliteConfig(path, 1, 1)).transactor
    yield path -> transactor

  private def migratedDatabase: Resource[IO, Transactor[IO]] =
    for
      path <- Resource.make(
        IO.blocking(Files.createTempFile("photography-album-schema", ".sqlite"))
      )(path => IO.blocking(Files.deleteIfExists(path)).void)
      transactor <- SqliteConnection.make[IO](SqliteConfig(path, 1, 1)).transactor
      flyway     <- FlywayMigrator.make[IO](path)
      _          <- Resource.eval(flyway.migrate)
    yield transactor
