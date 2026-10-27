package photography.app.config

import cats.effect.Async
import com.comcast.ip4s.{Host, Port}
import io.github.iltotore.iron.pureconfig.given
import photography.usecases.AlbumPageSize
import photography.domain.album.PhotoSource
import pureconfig.ConfigReader
import pureconfig.ConfigSource
import pureconfig.module.catseffect.syntax.*
import pureconfig.module.ip4s.*

import java.nio.file.Path

final case class AppConfig(sqlite: SqliteConfig, albums: AlbumsConfig, thumbnails: ThumbnailsConfig, http: HttpServerConfig) derives ConfigReader

object AppConfig:

  def load[F[_]: Async](source: ConfigSource): F[AppConfig] =
    source.at("photography").loadF[F, AppConfig]()

final case class SqliteConfig(path: Path, connectionAcquisitionThreads: Int, maxSessions: Int) derives ConfigReader

final case class AlbumsConfig(pageSize: AlbumPageSize, photoRoot: PhotoSource) derives ConfigReader

final case class ThumbnailsConfig(root: Path)

object ThumbnailsConfig:
  given ConfigReader[ThumbnailsConfig] =
    ConfigReader.forProduct1("root")(ThumbnailsConfig.apply)(using
      ConfigReader[Path].ensure(_.isAbsolute, _ => "Expected an absolute thumbnail root")
    )

final case class HttpServerConfig(host: Host, port: Port) derives ConfigReader
