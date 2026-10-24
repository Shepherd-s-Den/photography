package photography.adapters.sqlite

import cats.syntax.all.*
import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*
import photography.app.config.SqliteConfig

import java.nio.file.Files

final class SqliteConnectionSeamIntegrationTest extends CatsEffectSuite:
  test("should enforce foreign keys on every pooled connection"):
    buildTransactor.use: xa =>
      val invalidWrite = sql"INSERT INTO cover (album_id, spine_owner) VALUES ('missing', 'front')".update.run.transact(xa)

      (invalidWrite.attempt, invalidWrite.attempt).parTupled.map { case (first, second) =>
        assert(first.isLeft)
        assert(second.isLeft)
      }

  private def buildTransactor =
    for
      path <- Resource.make(IO.blocking(Files.createTempFile("connection-", ".sqlite")))(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).transactor
    yield xa
