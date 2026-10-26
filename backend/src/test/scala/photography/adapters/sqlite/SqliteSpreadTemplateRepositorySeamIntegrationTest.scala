package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Decoder
import io.circe.jawn.decode
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
import scala.io.{Codec, Source}

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

  test(s"should load all 27 original Saal chapter openings with $defaultChapterTemplateName first"):
    buildTransactor.use: xa =>
      val repository       = SqliteSpreadTemplateRepository.make[IO](xa)
      val chapterInventory = readInventory[List[(
          String,
          String,
          Vector[(Double, Double, Double, Double)],
          (Double, Double, Double, Double)
      )]]("chapter-start-templates.json")

      for
        expected <- chapterInventory
        loaded   <- repository.listChapterStartTemplates
      yield loaded match
        case ListChapterStartTemplatesResult.Listed(templates) =>
          val actual = templates.toList.map { template =>
            (template.id.value.toString, template.name: String, template.photoSlots.map(boxValues), boxValues(template.textSlot))
          }

          assertEquals(expected.size, 27)
          assertEquals(actual, expected)
          assertEquals(templates.head.name: String, defaultChapterTemplateName)
          assertEquals(templates.head.photoSlots.size, 4)
        case result => fail(s"Expected migrated chapter templates, obtained $result")

  test("should populate all 249 ISO countries through SQL"):
    buildTransactor.use: xa =>
      for
        expected <- readInventory[List[(String, String, String, String)]]("countries.json")
        actual   <- sql"SELECT id, code, name, flag FROM country ORDER BY code".query[(String, String, String, String)].to[List].transact(xa)
      yield
        assertEquals(actual.size, 249)
        assertEquals(actual, expected)

  test("should preserve existing country identity and template geometry when the seed is reapplied"):
    buildTransactor.use: xa =>
      val templateId      = "00000000-0000-0000-0000-000000000005"
      val countryId       = "90000000-0000-0000-0000-000000000001"
      val expectedRomania = (countryId, "Existing Romania")
      val seedSource      = Resource.fromAutoCloseable(IO.blocking(Source.fromResource("db/V2__seed_reference_catalogs.sql")(using Codec.UTF8)))
      val templateRows    = sql"SELECT id, template_id, kind, x, y, width, height FROM spread_template_slot ORDER BY id".query[(
          Long,
          String,
          String,
          Double,
          Double,
          Double,
          Double
      )].to[List]

      for
        _         <- sql"UPDATE country SET id = $countryId, name = 'Existing Romania' WHERE code = 'RO'".update.run.transact(xa)
        _         <- sql"UPDATE spread_template_slot SET width = 20 WHERE template_id = $templateId AND kind = 'text'".update.run.transact(xa)
        before    <- templateRows.transact(xa)
        seed      <- seedSource.use(source => IO.blocking(source.mkString))
        _         <- seed.split(";").toList.filter(_.trim.nonEmpty).traverse_(statement => Fragment.const(statement).update.run).transact(xa)
        after     <- templateRows.transact(xa)
        romania   <- sql"SELECT id, name FROM country WHERE code = 'RO'".query[(String, String)].unique.transact(xa)
        countries <- sql"SELECT count(*) FROM country".query[Int].unique.transact(xa)
        templates <- sql"SELECT count(*) FROM spread_template WHERE kind = 'chapter_start'".query[Int].unique.transact(xa)
      yield
        assertEquals(after, before)
        assertEquals(romania, expectedRomania)
        assertEquals(countries, 249)
        assertEquals(templates, 27)

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
    val databaseFile = Resource.make(IO.blocking(Files.createTempFile(Path.of("target"), "template-", ".sqlite")))(path =>
      IO.blocking(Files.deleteIfExists(path)).void
    )

    for
      path <- databaseFile
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa

  private def buildBox(x: Double, y: Double, width: Double, height: Double) =
    SlotBox(x = x.refineUnsafe, y = y.refineUnsafe, width = width.refineUnsafe, height = height.refineUnsafe)

  private def boxValues(box: SlotBox) =
    (box.x: Double, box.y: Double, box.width: Double, box.height: Double)

  private def readInventory[A: Decoder](name: String) =
    Resource.fromAutoCloseable(IO.blocking(Source.fromResource(s"reference/$name")(using Codec.UTF8))).use: source =>
      IO.blocking(source.mkString).flatMap(text => IO.fromEither(decode[A](text)))
