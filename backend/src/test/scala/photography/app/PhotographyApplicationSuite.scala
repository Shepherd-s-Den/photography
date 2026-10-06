package photography.app

import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import org.http4s.Uri
import org.http4s.ember.client.EmberClientBuilder

import com.comcast.ip4s.IpAddress
import java.net.ServerSocket
import java.nio.file.{Files, Path}
import java.util.Comparator

final class PhotographyApplicationSuite extends CatsEffectSuite:

  private def temporaryDirectory[A](use: Path => IO[A]): IO[A] =
    Resource
      .make(IO.blocking(Files.createTempDirectory("photography-service-test-")))(deleteTree)
      .use(use)

  private def deleteTree(root: Path): IO[Unit] =
    IO.blocking:
      val paths = Files.walk(root)
      try
        paths
          .sorted(Comparator.reverseOrder())
          .forEach(path =>
            val _ = Files.deleteIfExists(path)
            ()
          )
      finally paths.close()

  private def availablePort: IO[Int] =
    IO.blocking:
      val socket = new ServerSocket(0)
      try socket.getLocalPort
      finally socket.close()

  private def config(database: Path, port: Int): AppConfig =
    val server = HttpServerConfig.fromEnvironment(
      Map("PHOTOGRAPHY_HOST" -> "127.0.0.1", "PHOTOGRAPHY_PORT" -> port.toString)
    )
    server match
      case Right(value) => AppConfig(SqliteConfig(database, 1, 2), value)
      case Left(error)  => fail(error.getMessage)

  test("migration completes before the HTTP server serves readiness"):
    temporaryDirectory: directory =>
      for
        port <- availablePort
        applicationConfig = config(directory.resolve("photography.db"), port)
        result <- PhotographyApplication
          .make[IO](applicationConfig)
          .use: _ =>
            EmberClientBuilder.default[IO].build.use: client =>
              Uri.fromString(s"http://127.0.0.1:$port/health") match
                case Right(uri)  => client.expect[String](uri)
                case Left(error) => IO.raiseError[String](error)
      yield assertEquals(result, """{"status":"ok"}""")

  test("a migration failure prevents the application from acquiring its server"):
    temporaryDirectory: directory =>
      for
        port <- availablePort
        invalidDatabase = directory.resolve("missing").resolve("photography.db")
        result <- PhotographyApplication
          .make[IO](config(invalidDatabase, port))
          .use(_ => IO.unit)
          .attempt
      yield assert(result.isLeft)

  test("an invalid port prevents the application from acquiring its server"):
    temporaryDirectory: directory =>
      val loopback = IpAddress.fromString("127.0.0.1") match
        case Some(value) => value
        case None        => fail("127.0.0.1 must parse as an IP address")
      val invalidPortConfig = AppConfig(
        SqliteConfig(directory.resolve("photography.db"), 1, 2),
        HttpServerConfig(loopback, 65536)
      )

      PhotographyApplication
        .make[IO](invalidPortConfig)
        .use(_ => IO.unit)
        .attempt
        .map(result => assert(result.isLeft))
