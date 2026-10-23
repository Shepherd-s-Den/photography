package photography.adapters.sqlite

import cats.effect.{Async, Resource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.sqlite.{SQLiteConfig, SQLiteDataSource}
import org.typelevel.doobie.Transactor
import org.typelevel.doobie.util.ExecutionContexts
import photography.app.SqliteConfig

import scala.util.chaining.*

trait SqliteConnection[F[_]]:
  def transactor: Resource[F, Transactor[F]]

object SqliteConnection:

  def make[F[_]: Async](config: SqliteConfig): SqliteConnection[F] =
    new LiveSqliteConnection(config)

  final private class LiveSqliteConnection[F[_]: Async](config: SqliteConfig) extends SqliteConnection[F]:
    override def transactor: Resource[F, Transactor[F]] =
      for
        connectExecutionContext <- ExecutionContexts.fixedThreadPool[F](size = config.connectionAcquisitionThreads)
        dataSource              <- Resource.make(Async[F].blocking(createDataSource()))(ds => Async[F].blocking(ds.close()))
      yield Transactor.fromDataSource[F](dataSource, connectExecutionContext)

    private def createDataSource() =
      val driverConfig     = new SQLiteConfig().tap(_.enforceForeignKeys(true))
      val sqliteDataSource = new SQLiteDataSource(driverConfig).tap(_.setUrl(s"jdbc:sqlite:${config.path.toAbsolutePath.normalize()}"))
      new HikariConfig()
        .tap(_.setDataSource(sqliteDataSource))
        .tap(_.setMaximumPoolSize(config.maxSessions))
        .tap(_.setPoolName("photography-sqlite"))
        .pipe(new HikariDataSource(_))
