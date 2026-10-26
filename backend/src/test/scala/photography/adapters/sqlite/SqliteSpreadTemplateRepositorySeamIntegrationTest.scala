package photography.adapters.sqlite

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

import java.nio.file.{Files, Path}

final class SqliteSpreadTemplateRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]               = Slf4jLogger.getLogger[IO]
  private val defaultTemplateName        = "2×2 L + Synopsis R"
  private val defaultChapterTemplateName = "Text + 2P + 2L"

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
      val expectedNames     = List(defaultTemplateName, "1 Full L + Synopsis R", "3+2 L + Synopsis R", "P+2L L + Synopsis R")
      val expectedText      = buildBox(x = 50.104993, y = 0.914609, width = 49.601025, height = 98.170781)
      val expectedTextSlots = List.fill(4)(expectedText)

      repository.listAlbumStartTemplates.map:
        case ListAlbumStartTemplatesResult.Listed(templates) =>
          assertEquals(templates.toList.map(_.name), expectedNames)
          assertEquals(templates.toList.map(_.photoSlots), expectedPhotos)
          assertEquals(templates.toList.map(_.textSlot), expectedTextSlots)
        case result => fail(s"Expected migrated templates, obtained $result")

  test(s"should load the 27 chapter openings with $defaultChapterTemplateName first"):
    buildTransactor.use: xa =>
      val repository     = SqliteSpreadTemplateRepository.make[IO](xa)
      val expectedPhotos = Vector(
        buildBox(x = 25.199487, y = 0.914609, width = 23.666164, height = 98.170781),
        buildBox(x = 49.075638, y = 0.914609, width = 23.666164, height = 98.170781),
        buildBox(x = 72.951789, y = 0.914609, width = 26.754230, height = 48.758744),
        buildBox(x = 72.951789, y = 50.326646, width = 26.754230, height = 48.758744)
      )
      val expectedText = buildBox(x = 0.293982, y = 0.914609, width = 24.695519, height = 98.170781)

      repository.listChapterStartTemplates.map:
        case ListChapterStartTemplatesResult.Listed(templates) =>
          assertEquals(templates.size, 27)
          val identities = templates.toList.map(_.id.value.toString)

          assertEquals(identities, identities.sorted)
          assertEquals(identities.distinct.size, 27)
          assertEquals(templates.head.id.value.toString, "00000000-0000-0000-0000-000000000005")
          assertEquals(templates.head.name: String, defaultChapterTemplateName)
          assertEquals(templates.head.photoSlots, expectedPhotos)
          assertEquals(templates.head.textSlot, expectedText)
        case result => fail(s"Expected migrated chapter templates, obtained $result")

  test("should populate 249 distinct ISO countries with stable identities and flag paths"):
    buildTransactor.use: xa =>
      val inventory       = sql"SELECT count(*), count(DISTINCT code), count(DISTINCT id) FROM country".query[(Int, Int, Int)].unique
      val romania         = sql"SELECT id, name, flag FROM country WHERE code = 'RO'".query[(String, String, String)].unique
      val expectedRomania = ("10000000-0000-0000-0000-000000000001", "Romania", "/flags/ro.svg")

      for
        counts  <- inventory.transact(xa)
        country <- romania.transact(xa)
      yield
        assertEquals(counts, (249, 249, 249))
        assertEquals(country, expectedRomania)

  test("should reject malformed or unavailable chapter opening catalogs"):
    Vector(
      "DELETE FROM spread_template_slot WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      "DELETE FROM spread_template_slot WHERE kind = 'text' AND template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      "DELETE FROM spread_template_slot WHERE kind = 'photo' AND template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      "UPDATE spread_template_slot SET width = -1 WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      "UPDATE spread_template SET name = '' WHERE kind = 'chapter_start'",
      "UPDATE spread_template_slot SET kind = 'unknown' WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start')",
      """DELETE FROM spread_template_slot WHERE template_id IN (SELECT id FROM spread_template WHERE kind = 'chapter_start');
         DELETE FROM spread_template WHERE kind = 'chapter_start'"""
    ).traverse_ { corruption =>
      buildTransactor.use: xa =>
        val repository = SqliteSpreadTemplateRepository.make[IO](xa)
        val statements = corruption.split(";").toList.traverse_(statement => Fragment.const(statement).update.run)
        val corrupt    = sql"PRAGMA ignore_check_constraints = ON".update.run >> statements >> sql"PRAGMA ignore_check_constraints = OFF".update.run
        val expectedFailure = ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure)

        for
          _      <- corrupt.transact(xa)
          result <- repository.listChapterStartTemplates
        yield assertEquals(result, expectedFailure)
    }

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
        val expectedFailure = ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure)

        for
          _      <- corrupt.transact(xa)
          result <- repository.listAlbumStartTemplates
        yield assertEquals(result, expectedFailure)
    }

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "template-", ".sqlite"))

    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa

  private def buildBox(x: Double, y: Double, width: Double, height: Double) =
    SlotBox(x = x.refineUnsafe, y = y.refineUnsafe, width = width.refineUnsafe, height = height.refineUnsafe)
