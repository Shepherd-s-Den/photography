package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.typelevel.doobie.Fragment
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.app.config.SqliteConfig
import photography.domain.layout.*
import photography.ports.SpreadTemplateRepository.*

import java.nio.file.Files
import java.util.UUID

final class SqliteSpreadTemplateRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]        = Slf4jLogger.getLogger[IO]
  private val defaultTemplateName = "2×2 L + Synopsis R"

  test(s"should preserve the complete synopsis-right catalog with $defaultTemplateName first"):
    buildTransactor.use: xa =>
      val repository     = SqliteSpreadTemplateRepository.make[IO](xa)
      val expectedPhotos = List(
        Vector(
          buildBox(x = 0.293982, y = 0.914609, width = 24.695519, height = 48.758744),
          buildBox(x = 25.199487, y = 0.914609, width = 24.695519, height = 48.758744),
          buildBox(x = 0.293982, y = 50.326646, width = 24.695519, height = 48.758744),
          buildBox(x = 25.199487, y = 50.326646, width = 24.695519, height = 48.758744)
        ),
        Vector(buildBox(x = 0.293982, y = 0.914609, width = 49.601025, height = 98.170781)),
        Vector(
          buildBox(x = 0.293982, y = 0.914609, width = 16.393684, height = 38.870004),
          buildBox(x = 16.897652, y = 0.914609, width = 16.393684, height = 38.870004),
          buildBox(x = 33.501323, y = 0.914609, width = 16.393684, height = 38.870004),
          buildBox(x = 0.293982, y = 40.437905, width = 24.695519, height = 58.553948),
          buildBox(x = 25.199487, y = 40.437905, width = 24.695519, height = 58.553948)
        ),
        Vector(
          buildBox(x = 0.293982, y = 0.914609, width = 24.695519, height = 98.170781),
          buildBox(x = 25.199487, y = 0.914609, width = 24.695519, height = 48.758744),
          buildBox(x = 25.199487, y = 50.326646, width = 24.695519, height = 48.758744)
        )
      )
      val expectedNames = List(defaultTemplateName, "1 Full L + Synopsis R", "3+2 L + Synopsis R", "P+2L L + Synopsis R")
      val expectedText  = buildBox(x = 50.104993, y = 0.914609, width = 49.601025, height = 98.170781)

      repository.listAlbumStartTemplates.map:
        case ListAlbumStartTemplatesResult.Listed(templates) =>
          assertEquals(templates.toList.map(_.name), expectedNames)
          assertEquals(templates.toList.map(_.photoSlots), expectedPhotos)
          assertEquals(templates.toList.map(_.textSlot), List.fill(4)(expectedText))
        case result => fail(s"Expected migrated templates, obtained $result")

  test("should load complete chapter openings in identity order and reject an unavailable catalog"):
    buildTransactor.use: xa =>
      val repository = SqliteSpreadTemplateRepository.make[IO](xa)
      val id         = new UUID(0L, 20L).toString
      val seed       =
        for
          _ <- sql"INSERT INTO spread_template VALUES ($id, 'Chapter opening', 'chapter_start')".update.run
          _ <- sql"INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES ($id, 'photo', 0, 0, 50, 100)".update.run
          _ <- sql"INSERT INTO spread_template_slot (template_id, kind, x, y, width, height) VALUES ($id, 'text', 50, 0, 50, 100)".update.run
        yield ()
      val expected =
        SpreadTemplate.ChapterStart(
          SpreadTemplateId(new UUID(0L, 20L)),
          name = "Chapter opening",
          photoSlots = Vector(buildBox(x = 0, y = 0, width = 50, height = 100)),
          textSlot = buildBox(x = 50, y = 0, width = 50, height = 100)
        )

      for
        unavailable <- repository.listChapterStartTemplates
        _           <- seed.transact(xa)
        loaded      <- repository.listChapterStartTemplates
        _           <- sql"DELETE FROM spread_template_slot WHERE template_id = $id AND kind = 'text'".update.run.transact(xa)
        malformed   <- repository.listChapterStartTemplates
      yield
        assertEquals(unavailable, ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure))
        assertEquals(loaded, ListChapterStartTemplatesResult.Listed(NonEmptyList.one(expected)))
        assertEquals(malformed, ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure))

  test("should reject malformed template catalogs and report database failures explicitly"):
    Vector(
      "DELETE FROM spread_template_slot",
      "UPDATE spread_template_slot SET width = -1",
      "UPDATE spread_template SET name = ''",
      "UPDATE spread_template_slot SET kind = 'unknown'",
      "UPDATE spread_template_slot SET kind = 'photo' WHERE kind = 'text'",
      "DROP TABLE spread_template_slot"
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteSpreadTemplateRepository.make[IO](xa)
        val corrupt    = sql"PRAGMA ignore_check_constraints = ON".update.run >>
          Fragment.const(corruption).update.run >> sql"PRAGMA ignore_check_constraints = OFF".update.run

        for
          _      <- corrupt.transact(xa)
          result <- repository.listAlbumStartTemplates
        yield assertEquals(result, ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure))
    }

  private def buildTransactor =
    for
      path <- Resource.make(IO.blocking(Files.createTempFile("template-", ".sqlite")))(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa

  private def buildBox(x: Double, y: Double, width: Double, height: Double) =
    SlotBox(x = x.refineUnsafe, y = y.refineUnsafe, width = width.refineUnsafe, height = height.refineUnsafe)
