package photography.app.config

import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import munit.CatsEffectSuite
import photography.domain.album.ThumbnailQuality
import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderException

import java.nio.file.Path
import scala.jdk.CollectionConverters.*

final class AppConfigSeamIntegrationTest extends CatsEffectSuite:

  test("should load configured absolute photo and thumbnail roots"):
    AppConfig.load[IO](ConfigSource.resources("app-config-seam/application.conf")).map: config =>
      assertEquals(config.albums.photoRoot: String, "/library/photos")
      assertEquals(config.thumbnails.root, Path.of("/library/thumbnails"))

  test(s"should default thumbnail quality to ${ThumbnailQuality.Standard} without environment overrides"):
    val source = buildConfigSource(settings = "", environment = Map.empty[String, String])

    val loaded = AppConfig.load[IO](source)

    loaded.map: config =>
      assertEquals(config.thumbnails.quality, ThumbnailQuality.Standard)
      assertEquals(config.thumbnails.root, Path.of("/thumbnails"))

  Vector("low" -> ThumbnailQuality.Low, "standard" -> ThumbnailQuality.Standard, "high" -> ThumbnailQuality.High).foreach:
    case (configured, expectedQuality) =>
      test(s"should decode configured thumbnail quality $expectedQuality"):
        val source = buildConfigSource(settings = s"""photography.thumbnails.quality = "$configured"""", environment = Map.empty[String, String])

        val loaded = AppConfig.load[IO](source)

        loaded.map(config => assertEquals(config.thumbnails.quality, expectedQuality))

  test("should apply thumbnail quality and root environment overrides before decoding"):
    val environment = Map("PHOTOGRAPHY_THUMBNAIL_QUALITY" -> "high", "PHOTOGRAPHY_THUMBNAIL_ROOT" -> "/library/overridden-thumbnails")
    val source      = buildConfigSource(settings = "", environment = environment)

    val loaded = AppConfig.load[IO](source)

    loaded.map: config =>
      assertEquals(config.thumbnails.quality, ThumbnailQuality.High)
      assertEquals(config.thumbnails.root, Path.of("/library/overridden-thumbnails"))

  test("should reject unknown and malformed thumbnail quality with its configuration path"):
    Vector("\"unknown\"", "\"\"", "42", "true", "[]", "{}", "null").traverse_ { quality =>
      val source = buildConfigSource(settings = s"photography.thumbnails.quality = $quality", environment = Map.empty[String, String])

      val loaded = AppConfig.load[IO](source).attempt

      loaded.map:
        case Left(error: ConfigReaderException[?]) => assert(error.getMessage.contains("photography.thumbnails.quality"), clue = quality)
        case unexpected                            => fail(s"Expected a quality configuration failure for $quality, received $unexpected")
    }

  test("should reject invalid thumbnail environment overrides rather than retaining defaults"):
    Vector(
      Map("PHOTOGRAPHY_THUMBNAIL_QUALITY" -> "unknown") -> "photography.thumbnails.quality",
      Map("PHOTOGRAPHY_THUMBNAIL_QUALITY" -> "")        -> "photography.thumbnails.quality",
      Map("PHOTOGRAPHY_THUMBNAIL_ROOT" -> "relative")   -> "photography.thumbnails.root"
    ).traverse_ { case (environment, path) =>
      val source = buildConfigSource(settings = "", environment = environment)

      val loaded = AppConfig.load[IO](source).attempt

      loaded.map:
        case Left(error: ConfigReaderException[?]) => assert(error.getMessage.contains(path), clue = environment)
        case unexpected                            => fail(s"Expected a configuration failure at $path, received $unexpected")
    }

  test("should reject relative roots before constructing application services"):
    Vector("albums.photo-root" -> "relative/photos", "thumbnails.root" -> "relative/thumbnails").traverse_ { case (key, path) =>
      val source = ConfigSource.string(s"""photography.$key = "$path"""")
        .withFallback(ConfigSource.resources("app-config-seam/application.conf"))

      val loaded = AppConfig.load[IO](source).attempt

      loaded.map:
        case Left(error: ConfigReaderException[?]) => assert(error.getMessage.contains(s"photography.$key"), clue = path)
        case unexpected                            => fail(s"Expected an absolute-root configuration failure, received $unexpected")
    }

  private def buildConfigSource(settings: String, environment: Map[String, String]) =
    val configured    = ConfigFactory.parseString(settings).withFallback(ConfigFactory.parseResources("application.conf"))
    val substitutions = ConfigFactory.parseMap(environment.asJava)
    val options       = ConfigResolveOptions.defaults().setUseSystemEnvironment(false)
    ConfigSource.fromConfig(configured.withFallback(substitutions).resolve(options))
