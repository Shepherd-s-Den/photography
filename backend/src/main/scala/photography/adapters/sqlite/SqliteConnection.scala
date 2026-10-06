package photography.adapters.sqlite

import cats.effect.{Async, Resource}
import org.sqlite.{SQLiteConfig, SQLiteDataSource}
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.util.ExecutionContexts

import scala.util.chaining.*

import java.nio.file.Path

trait SqliteConnection[F[_]]:
  def transactor: Transactor[F]

object SqliteConnection:
  def make[F[_]: Async](configuredPath: Path): Resource[F, SqliteConnection[F]] =
    for
      connectExecutionContext <- ExecutionContexts.fixedThreadPool[F](size = 8)
      dataSource              <- Resource.eval(Async[F].blocking(createDataSource(configuredPath)))
    yield new LiveSqliteConnection(Transactor.fromDataSource[F](dataSource, connectExecutionContext))

  private def createDataSource(configuredPath: Path): SQLiteDataSource =
    val path = configuredPath.toAbsolutePath.normalize()
    val config = new SQLiteConfig().tap(_.enforceForeignKeys(true))
    new SQLiteDataSource(config).tap(_.setUrl(s"jdbc:sqlite:$path"))

  final private class LiveSqliteConnection[F[_]](override val transactor: Transactor[F])
      extends SqliteConnection[F]
