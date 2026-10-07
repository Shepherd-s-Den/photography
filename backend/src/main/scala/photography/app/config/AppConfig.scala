package photography.app

import cats.effect.Async
import cats.syntax.all.*
import pureconfig.ConfigReader
import pureconfig.ConfigSource
import pureconfig.module.catseffect.syntax.*

import java.nio.file.Path

final case class SqliteConfig(
    path: Path,
    connectionAcquisitionThreads: Int,
    maxSessions: Int
) derives ConfigReader

final case class AppConfig(sqlite: SqliteConfig, http: HttpServerConfig)

object AppConfig:

  def load[F[_]: Async]: F[AppConfig] =
    loadWithEnvironment(sys.env)

  private[app] def loadWithEnvironment[F[_]: Async](environment: Map[String, String]): F[AppConfig] =
    for
      sqlite <- ConfigSource.defaultApplication.at("photography.sqlite").loadF[F, SqliteConfig]()
      http   <- Async[F].blocking(HttpServerConfig.fromEnvironment(environment)).flatMap(_.liftTo[F])
      localPath = environment
        .get("PHOTOGRAPHY_SQLITE_PATH")
        .filter(_.nonEmpty)
        .map(value => Path.of(value))
        .getOrElse(sqlite.path)
    yield AppConfig(sqlite.copy(path = localPath), http)
