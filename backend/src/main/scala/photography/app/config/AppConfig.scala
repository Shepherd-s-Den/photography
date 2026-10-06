package photography.app

import cats.effect.Async
import pureconfig.ConfigReader
import pureconfig.ConfigSource
import pureconfig.module.catseffect.syntax.*

final case class SqliteConfig(path: String) derives ConfigReader

final case class AppConfig(sqlite: SqliteConfig) derives ConfigReader

object AppConfig:

  def load[F[_]: Async]: F[AppConfig] =
    ConfigSource.defaultApplication.at("photography").loadF[F, AppConfig]()
