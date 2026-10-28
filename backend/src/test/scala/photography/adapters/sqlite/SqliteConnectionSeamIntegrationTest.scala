package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import munit.CatsEffectSuite
import org.sqlite.{SQLiteConnection, SQLiteErrorCode, SQLiteException}
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.implicits.*
import photography.app.config.SqliteConfig

import java.nio.file.{Files, Path}

final class SqliteConnectionSeamIntegrationTest extends CatsEffectSuite:
  test("should enforce foreign keys on every pooled connection"):
    buildTransactor.use: xa =>
      (xa.connect(xa.kernel), xa.connect(xa.kernel)).tupled.use { case (first, second) =>
        val distinctConnections = IO.blocking:
          val firstIdentity  = System.identityHashCode(first.unwrap(classOf[SQLiteConnection]))
          val secondIdentity = System.identityHashCode(second.unwrap(classOf[SQLiteConnection]))
          firstIdentity =!= secondIdentity
        val connections = Vector(first, second)

        distinctConnections.flatMap: distinct =>
          assert(distinct)
          connections.traverse_ { connection =>
            val held         = Transactor.fromConnection[IO](connection, logHandler = None)
            val invalidWrite = sql"INSERT INTO cover (album_id, spine_owner) VALUES ('missing', 'front')".update.run

            for
              enabled  <- sql"PRAGMA foreign_keys".query[Int].unique.transact(held)
              rejected <- invalidWrite.transact(held).attempt
            yield
              assertEquals(enabled, 1)
              rejected match
                case Left(error: SQLiteException) => assertEquals(error.getResultCode, SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY)
                case result                       => fail(s"Expected a foreign-key constraint rejection, obtained $result")
          }
      }

  private def buildTransactor =
    val acquireDatabase = IO.blocking(Files.createTempFile(Path.of("target"), "connection-", ".sqlite"))

    for
      path <- Resource.make(acquireDatabase)(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).buildTransactor
    yield xa
