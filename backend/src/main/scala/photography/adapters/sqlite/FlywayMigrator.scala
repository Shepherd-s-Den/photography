package photography.adapters.sqlite

import cats.effect.{Async, Resource}
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location}

import java.nio.file.Path

object FlywayMigrator:

  def make[F[_]: Async](configuredPath: Path): Resource[F, Fly4s[F]] =
    Fly4s.make[F](
      buildJdbcUrl(configuredPath),
      user = Option.empty[String],
      password = Option.empty[Array[Char]],
      Fly4sConfig(table = "flyway", locations = List(Location("db")), connectRetries = 3)
    )

  private def buildJdbcUrl(configuredPath: Path) =
    s"jdbc:sqlite:${configuredPath.toAbsolutePath.normalize()}"
