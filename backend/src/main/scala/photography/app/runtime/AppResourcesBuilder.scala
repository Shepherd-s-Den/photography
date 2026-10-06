package photography.app

import cats.effect.{Async, Resource}
import fly4s.Fly4s
import org.typelevel.doobie.Transactor
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}

final case class AppResources[F[_]](sqlite: Transactor[F], flyway: Fly4s[F])

trait AppResourcesBuilder:
  def build[F[_]: Async]: Resource[F, AppResources[F]]

object AppResourcesBuilder:

  def make(config: AppConfig): AppResourcesBuilder =
    new LiveAppResourcesBuilder(config)

  private class LiveAppResourcesBuilder(config: AppConfig) extends AppResourcesBuilder:

    override def build[F[_]: Async]: Resource[F, AppResources[F]] =
      for
        sqlite <- SqliteConnection.make[F](config.sqlite).transactor
        flyway <- FlywayMigrator.make[F](config.sqlite.path)
      yield AppResources(sqlite, flyway)
