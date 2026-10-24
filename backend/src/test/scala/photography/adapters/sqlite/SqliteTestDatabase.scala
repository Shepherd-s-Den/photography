package photography.adapters.sqlite

import cats.effect.{IO, Resource}
import org.typelevel.doobie.Transactor
import photography.app.config.SqliteConfig

import java.nio.file.Files

object SqliteTestDatabase:
  def resource: Resource[IO, Transactor[IO]] =
    for
      path <- Resource.make(IO.blocking(Files.createTempFile("photography-seam-", ".sqlite")))(path => IO.blocking(Files.deleteIfExists(path)).void)
      _    <- FlywayMigrator.make[IO](path).evalMap(_.migrate)
      xa   <- SqliteConnection.make[IO](SqliteConfig(path, connectionAcquisitionThreads = 2, maxSessions = 2)).transactor
    yield xa
