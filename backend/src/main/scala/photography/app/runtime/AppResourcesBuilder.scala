package photography.app

import cats.effect.{Async, Resource}
import fly4s.Fly4s
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}

final case class AppResources[F[_]](sqlite: SqliteConnection, flyway: Fly4s[F])

trait AppResourcesBuilder:
  def build[F[_]: Async]: Resource[F, AppResources[F]]

object AppResourcesBuilder:

  def make[F[_]: Async](config: AppConfig): Resource[F, AppResources[F]] =
    new LiveAppResourcesBuilder(config).build

  private class LiveAppResourcesBuilder(config: AppConfig) extends AppResourcesBuilder:

    override def build[F[_]: Async]: Resource[F, AppResources[F]] =
      for
        sqlite <- Resource.eval(SqliteConnection.make[F](config.sqlite.path))
        flyway <- FlywayMigrator.make[F](sqlite)
      yield AppResources(sqlite, flyway)
