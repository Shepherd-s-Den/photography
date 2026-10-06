package photography.app

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.Path

final class AppConfigSuite extends CatsEffectSuite:

  test("loads application settings from the process environment"):
    AppConfig.load[IO].map: actual =>
      assert(actual.sqlite.path.toString.nonEmpty)
      assert(actual.http.port >= 1 && actual.http.port <= 65535)

  test("loads the configured SQLite settings and HTTP defaults"):
    AppConfig.loadWithEnvironment[IO](Map.empty).map: actual =>
      assertEquals(actual.sqlite.path, Path.of("/data/photography.db"))
      assertEquals(actual.sqlite.connectionAcquisitionThreads, 2)
      assertEquals(actual.sqlite.maxSessions, 8)
      assertEquals(actual.http.host.toString, "127.0.0.1")
      assertEquals(actual.http.port, 8080)

  test("uses the local SQLite path and HTTP overrides"):
    val environment = Map(
      "PHOTOGRAPHY_SQLITE_PATH" -> "/tmp/photography-test.db",
      "PHOTOGRAPHY_HOST"        -> "localhost",
      "PHOTOGRAPHY_PORT"        -> "8091"
    )

    AppConfig.loadWithEnvironment[IO](environment).map: actual =>
      assertEquals(actual.sqlite.path, Path.of("/tmp/photography-test.db"))
      assertEquals(actual.http.port, 8091)
      assert(Set("127.0.0.1", "::1").contains(actual.http.host.toString))
