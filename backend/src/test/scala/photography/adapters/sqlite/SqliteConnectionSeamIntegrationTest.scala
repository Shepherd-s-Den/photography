package photography.adapters.sqlite

import cats.syntax.all.*
import munit.CatsEffectSuite
import org.typelevel.doobie.implicits.*

final class SqliteConnectionSeamIntegrationTest extends CatsEffectSuite:
  test("should enforce foreign keys on every pooled connection"):
    SqliteTestDatabase.resource.use: xa =>
      val invalidWrite = sql"INSERT INTO cover (album_id, spine_owner) VALUES ('missing', 'front')".update.run.transact(xa)

      (invalidWrite.attempt, invalidWrite.attempt).parTupled.map { case (first, second) =>
        assert(first.isLeft)
        assert(second.isLeft)
      }
