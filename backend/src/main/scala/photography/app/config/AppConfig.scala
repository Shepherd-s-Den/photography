package photography.app

import cats.effect.Async
import com.comcast.ip4s.{Host, Port}
import pureconfig.ConfigReader
import pureconfig.ConfigSource
import pureconfig.module.catseffect.syntax.*
import pureconfig.module.ip4s.*

import java.nio.file.Path

final case class SqliteConfig(path: Path, connectionAcquisitionThreads: Int, maxSessions: Int) derives ConfigReader

final case class AppConfig(sqlite: SqliteConfig, http: AppConfig.HttpServerConfig)
    derives ConfigReader

object AppConfig:

  final case class HttpServerConfig(host: Host, port: Port) derives ConfigReader

  def load[F[_]: Async]: F[AppConfig] =
    ConfigSource.defaultApplication.at("photography").loadF[F, AppConfig]()
