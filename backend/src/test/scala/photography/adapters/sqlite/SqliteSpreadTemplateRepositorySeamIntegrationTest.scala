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

import java.nio.file.Files

final class SqliteSpreadTemplateRepositorySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]        = Slf4jLogger.getLogger[IO]
  private val defaultTemplateName = "2×2 L + Synopsis R"

  test(s"should preserve the complete synopsis-right catalog with $defaultTemplateName first"):
    buildTransactor.use: xa =>
      val repository = SqliteSpreadTemplateRepository.make[IO](xa)

      repository.listAlbumStartTemplates.map:
        case ListAlbumStartTemplatesResult.Listed(templates) =>
          val synopsis      = SpreadTemplateSlot.Text(buildBox(50.104993, 0.914609, 49.601025, 98.170781))
          val expectedSlots = List(
            List(
              SpreadTemplateSlot.Photo(buildBox(0.293982, 0.914609, 24.695519, 48.758744)),
              SpreadTemplateSlot.Photo(buildBox(25.199487, 0.914609, 24.695519, 48.758744)),
              SpreadTemplateSlot.Photo(buildBox(0.293982, 50.326646, 24.695519, 48.758744)),
              SpreadTemplateSlot.Photo(buildBox(25.199487, 50.326646, 24.695519, 48.758744)),
              synopsis
            ),
            List(SpreadTemplateSlot.Photo(buildBox(0.293982, 0.914609, 49.601025, 98.170781)), synopsis),
            List(
              SpreadTemplateSlot.Photo(buildBox(0.293982, 0.914609, 16.393684, 38.870004)),
              SpreadTemplateSlot.Photo(buildBox(16.897652, 0.914609, 16.393684, 38.870004)),
              SpreadTemplateSlot.Photo(buildBox(33.501323, 0.914609, 16.393684, 38.870004)),
              SpreadTemplateSlot.Photo(buildBox(0.293982, 40.437905, 24.695519, 58.553948)),
              SpreadTemplateSlot.Photo(buildBox(25.199487, 40.437905, 24.695519, 58.553948)),
              synopsis
            ),
            List(
              SpreadTemplateSlot.Photo(buildBox(0.293982, 0.914609, 24.695519, 98.170781)),
              SpreadTemplateSlot.Photo(buildBox(25.199487, 0.914609, 24.695519, 48.758744)),
              SpreadTemplateSlot.Photo(buildBox(25.199487, 50.326646, 24.695519, 48.758744)),
              synopsis
            )
          )
          val expectedNames = List(defaultTemplateName, "1 Full L + Synopsis R", "3+2 L + Synopsis R", "P+2L L + Synopsis R")
          assertEquals(templates.toList.map(_.name), expectedNames)
          assertEquals(templates.toList.map(_.kind), List.fill(4)(SpreadTemplateKind.AlbumStart))
          assertEquals(templates.toList.map(_.slots.toList), expectedSlots)
        case result => fail(s"Expected migrated templates, obtained $result")

  test("should reject an empty or malformed template catalog and distinguish database failures"):
    Vector(
      "DELETE FROM spread_template_slot"                 -> Failure.InvalidSpreadData,
      "UPDATE spread_template_slot SET width = -1"       -> Failure.InvalidSpreadData,
      "UPDATE spread_template SET name = ''"             -> Failure.InvalidSpreadData,
      "UPDATE spread_template_slot SET kind = 'unknown'" -> Failure.InvalidSpreadData,
      "DROP TABLE spread_template_slot"                  -> Failure.DatabaseFailure
    ).traverse_ { case (corruption, expectedFailure) =>
      buildTransactor.use: xa =>
        val repository = SqliteSpreadTemplateRepository.make[IO](xa)
        val corrupt    = sql"PRAGMA ignore_check_constraints = ON".update.run >>
          Fragment.const(corruption).update.run >> sql"PRAGMA ignore_check_constraints = OFF".update.run

        for
          _      <- corrupt.transact(xa)
          result <- repository.listAlbumStartTemplates
        yield assertEquals(result, ListAlbumStartTemplatesResult.Failed(expectedFailure))
    }

  private def buildTransactor =
    for
      path <- Resource.make(IO.blocking(Files.createTempFile("template-", ".sqlite")))(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).transactor
    yield xa

  private def buildBox(x: Double, y: Double, width: Double, height: Double) =
    SlotBox(x.refineUnsafe, y.refineUnsafe, width.refineUnsafe, height.refineUnsafe)
