package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import org.typelevel.doobie.*
import org.typelevel.doobie.implicits.*
import photography.app.SqliteConfig

import java.nio.file.Files

final class AlbumSchemaSeamIntegrationTest extends CatsEffectSuite:

  test("should create album creation time and seed one text-only AlbumStart template"):
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

  private def migratedDatabase: Resource[IO, Transactor[IO]] =
    for
      path <- Resource.make(
        IO.blocking(Files.createTempFile("photography-album-schema", ".sqlite"))
      )(path => IO.blocking(Files.deleteIfExists(path)).void)
      transactor <- SqliteConnection.make[IO](SqliteConfig(path, 1, 1)).transactor
      flyway     <- FlywayMigrator.make[IO](path)
      _          <- Resource.eval(flyway.migrate)
    yield transactor
