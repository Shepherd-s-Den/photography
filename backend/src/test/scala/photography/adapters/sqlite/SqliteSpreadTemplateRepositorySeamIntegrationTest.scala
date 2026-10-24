package photography.adapters.sqlite

import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.Fragment
import org.typelevel.doobie.implicits.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.layout.{SpreadTemplateKind, SpreadTemplateSlot}
import photography.ports.SpreadTemplateRepository.*

final class SqliteSpreadTemplateRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  test("should migrate and list the four synopsis-right templates in ID order with the 2x2 default first"):
    SqliteTestDatabase.resource.use: xa =>
      val repository = SqliteSpreadTemplateRepository.make[IO](xa)

      repository.listAlbumStartTemplates.map:
        case ListAlbumStartTemplatesResult.Listed(templates) =>
          assertEquals(templates.toList.map(_.name), List("2×2 L + Synopsis R", "1 Full L + Synopsis R", "3+2 L + Synopsis R", "P+2L L + Synopsis R"))
          assertEquals(templates.toList.map(_.kind), List.fill(4)(SpreadTemplateKind.AlbumStart))
          assertEquals(templates.toList.map(_.slots.length), List(5, 2, 6, 4))
          templates.toList.foreach: template =>
            assertEquals(template.slots.toList.count { case SpreadTemplateSlot.Text(_) => true; case _ => false }, 1)
            template.slots.toList.collect { case SpreadTemplateSlot.Text(box) => box }.foreach(box => assert(box.x > 50.0))
          val expectedSynopsisBox = (50.104993, 0.914609, 49.601025, 98.170781)
          val actualSynopsisBoxes = templates.toList.flatMap(_.slots.toList.collect:
            case SpreadTemplateSlot.Text(box) => ((box.x: Double), (box.y: Double), (box.width: Double), (box.height: Double)))
          assertEquals(actualSynopsisBoxes, List.fill(4)(expectedSynopsisBox))
        case result => fail(s"Expected migrated templates, obtained $result")

  test("should reject an empty or malformed template catalog and distinguish database failures"):
    Vector(
      "DELETE FROM spread_template_slot"           -> Failure.InvalidSpreadData,
      "UPDATE spread_template_slot SET width = -1" -> Failure.InvalidSpreadData,
      "DROP TABLE spread_template_slot"            -> Failure.DatabaseFailure
    ).traverse_ { (corruption, expectedFailure) =>
      SqliteTestDatabase.resource.use: xa =>
        val repository = SqliteSpreadTemplateRepository.make[IO](xa)

        for
          _      <- Fragment.const(corruption).update.run.transact(xa)
          result <- repository.listAlbumStartTemplates
        yield assertEquals(result, ListAlbumStartTemplatesResult.Failed(expectedFailure))
    }

  test("should enforce foreign keys on every pooled connection"):
    SqliteTestDatabase.resource.use: xa =>
      val invalidWrite = sql"INSERT INTO cover (album_id, spine_owner) VALUES ('missing', 'front')".update.run.transact(xa)

      (invalidWrite.attempt, invalidWrite.attempt).parTupled.map: (first, second) =>
        assert(first.isLeft)
        assert(second.isLeft)
