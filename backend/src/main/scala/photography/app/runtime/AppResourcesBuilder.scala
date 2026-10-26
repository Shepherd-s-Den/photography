package photography.app.runtime

import cats.effect.{Async, Resource}
import fly4s.Fly4s
import org.typelevel.doobie.Transactor
import photography.adapters.sqlite.{FlywayMigrator, SqliteConnection}
import photography.app.config.SqliteConfig

trait AppResourcesBuilder:
  def build[F[_]: Async]: Resource[F, AppResourcesBuilder.AppResources[F]]

object AppResourcesBuilder:

  def make(config: SqliteConfig): AppResourcesBuilder =
    new LiveAppResourcesBuilder(config)

  final private class LiveAppResourcesBuilder(config: SqliteConfig) extends AppResourcesBuilder:

    override def build[F[_]: Async]: Resource[F, AppResourcesBuilder.AppResources[F]] =
      for
        sqlite <- SqliteConnection.make[F](config).buildTransactor
        flyway <- FlywayMigrator.make[F](config.path)
      yield AppResources(sqlite, flyway)

  final case class AppResources[F[_]](sqlite: Transactor[F], flyway: Fly4s[F])
