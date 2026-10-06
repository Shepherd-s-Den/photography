package photography.adapters.sqlite

import cats.effect.{Async, Resource}
import org.sqlite.SQLiteDataSource

import java.nio.file.{Files, Path, Paths}
import java.sql.Connection
import scala.util.Using

trait SqliteConnection:
  private[sqlite] def jdbcUrl: String
  def open[F[_]: Async]: Resource[F, Connection]

object SqliteConnection:
  def make[F[_]: Async](configuredPath: String): F[SqliteConnection] =
    Async[F].blocking {
      val path = databasePath(configuredPath)
      val dataSource = new SQLiteDataSource()
      val jdbcUrl = s"jdbc:sqlite:$path"
      dataSource.setUrl(jdbcUrl)
      new LiveSqliteConnection(dataSource, jdbcUrl)
    }

  private def databasePath(configuredPath: String): Path =
    val path = Paths.get(configuredPath).toAbsolutePath.normalize()
    val parent = path.getParent
    if parent == null || !Files.isDirectory(parent) then
      throw new IllegalArgumentException(s"SQLite database parent directory does not exist: $parent")
    path

  private final class LiveSqliteConnection(dataSource: SQLiteDataSource, url: String) extends SqliteConnection:
    override private[sqlite] def jdbcUrl: String = url

    override def open[F[_]: Async]: Resource[F, Connection] =
      for
        connection <- Resource.make(Async[F].blocking(dataSource.getConnection()))(connection =>
          Async[F].blocking(connection.close())
        )
        _ <- Resource.eval(Async[F].blocking(enableForeignKeys(connection)))
      yield connection

    private def enableForeignKeys(connection: Connection): Unit =
      val _ = Using.resource(connection.createStatement())(_.execute("PRAGMA foreign_keys = ON"))
