package photography.adapters.sqlite

import cats.effect.{Async, Resource}
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location}

object FlywayMigrator:

  def make[F[_]: Async](sqlite: SqliteConnection): Resource[F, Fly4s[F]] =
    Fly4s.make[F](
      sqlite.jdbcUrl,
      user = Option.empty[String],
      password = Option.empty[Array[Char]],
      Fly4sConfig(table = "flyway", locations = List(Location("db")), connectRetries = 3)
    )
