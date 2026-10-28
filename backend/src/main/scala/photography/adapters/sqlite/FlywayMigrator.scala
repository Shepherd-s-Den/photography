package photography.adapters.sqlite

import cats.effect.{Async, Resource}
import fly4s.Fly4s
import fly4s.data.{Fly4sConfig, Location}
import org.typelevel.doobie.util.ExecutionContexts

import java.nio.file.Path

object FlywayMigrator:

  def make[F[_]: Async](configuredPath: Path): Resource[F, Fly4s[F]] =
    val jdbcUrl = s"jdbc:sqlite:${configuredPath.toAbsolutePath.normalize()}"
    val config  = Fly4sConfig(table = "flyway", locations = List(Location("db")), connectRetries = 3)
    // fly4s 2.0.0 closes JDBC in delay; shift acquisition and release while this managed executor is alive.
    ExecutionContexts.fixedThreadPool[F](size = 1).flatMap: blockingExecutionContext =>
      Fly4s.make[F](jdbcUrl, user = Option.empty[String], password = Option.empty[Array[Char]], config).evalOn(blockingExecutionContext)
