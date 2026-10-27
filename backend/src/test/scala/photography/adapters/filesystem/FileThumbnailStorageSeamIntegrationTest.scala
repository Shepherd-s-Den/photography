package photography.adapters.filesystem

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.*
import photography.ports.ThumbnailStorage.*
import java.nio.file.{Files, Path}
import java.util.UUID

final class FileThumbnailStorageSeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO] = Slf4jLogger.getLogger[IO]
  private val location     = ThumbnailLocation(new UUID(0L, 1L))
  private val bytes        = Vector(1.toByte, 2.toByte).refineEither[ThumbnailBytesConstraint].fold(message => fail(message), identity)
  private val image        = EncodedThumbnail(bytes, ThumbnailDimensions(width = 1, height = 2), ThumbnailQuality.Standard)

  test("should store one exclusive thumbnail and idempotently remove only its final and partial artifacts"):
    buildDirectory.use: root =>
      val storage  = FileThumbnailStorage.make[IO](root)
      val file     = root.resolve(s"${location.value}.jpg")
      val partial  = root.resolve(s"${location.value}.tmp")
      val original = root.resolve("unrelated.jpg")
      val expected =
        Thumbnail(location, version = "a12871fee210fb8619291eaea194581cbd2531e4b23759d225f6806923f63222", image.dimensions, image.quality, bytes = 2)

      for
        stored    <- storage.storeThumbnail(location, image)
        duplicate <- storage.storeThumbnail(location, image)
        content   <- IO.blocking(Files.readAllBytes(file).toVector)
        _         <- IO.blocking(Files.write(partial, Array(3.toByte)))
        _         <- IO.blocking(Files.write(original, Array(4.toByte)))
        removed   <- storage.removeThumbnail(location)
        repeated  <- storage.removeThumbnail(location)
        remains   <- IO.blocking((Files.exists(file), Files.exists(partial), Files.readAllBytes(original).toVector))
        _         <- IO.blocking(Files.createSymbolicLink(file, original))
        _         <- storage.removeThumbnail(location)
        untouched <- IO.blocking(Files.readAllBytes(original).toVector)
        missing   <- FileThumbnailStorage.make[IO](root.resolve("absent")).removeThumbnail(location)
      yield
        assertEquals(stored, StoreResult.Stored(expected))
        assertEquals(duplicate, StoreResult.Failed)
        assertEquals(content, bytes: Vector[Byte])
        assertEquals(removed, RemoveResult.Removed)
        assertEquals(repeated, RemoveResult.Removed)
        assertEquals(remains, (false, false, Vector(4.toByte)))
        assertEquals(untouched, Vector(4.toByte))
        assertEquals(missing, RemoveResult.Removed)

  test("should report invalid storage and cleanup explicitly without following a root symlink"):
    buildDirectory.use: root =>
      val link           = root.resolve("link")
      val outside        = root.resolve("outside/child")
      val outsideFinal   = outside.resolve(s"${location.value}.jpg")
      val outsidePartial = outside.resolve(s"${location.value}.tmp")

      val prepareOutside =
        for
          _ <- IO.blocking(Files.createDirectories(outside))
          _ <- IO.blocking(Files.write(outsideFinal, Array(7.toByte)))
          _ <- IO.blocking(Files.write(outsidePartial, Array(8.toByte)))
          _ <- IO.blocking(Files.createSymbolicLink(link, root.resolve("outside")))
        yield ()
      val invalidRoots = Vector(link, link.resolve("child"), Path.of("relative"), root.resolve("link/../outside/child"))

      prepareOutside >> invalidRoots.traverse_ { invalidRoot =>
        for
          stored    <- FileThumbnailStorage.make[IO](invalidRoot).storeThumbnail(location, image)
          removed   <- FileThumbnailStorage.make[IO](invalidRoot).removeThumbnail(location)
          untouched <- IO.blocking((Files.readAllBytes(outsideFinal).toVector, Files.readAllBytes(outsidePartial).toVector))
        yield
          assertEquals(stored, StoreResult.Failed)
          assertEquals(removed, RemoveResult.Failed)
          assertEquals(untouched, (Vector(7.toByte), Vector(8.toByte)))
      }

  private def buildDirectory =
    Resource.make(IO.blocking(Files.createTempDirectory(Path.of("target"), "thumbnails-").toAbsolutePath)): root =>
      val artifacts = Vector(
        s"${location.value}.jpg",
        s"${location.value}.tmp",
        "unrelated.jpg",
        "link",
        s"outside/child/${location.value}.jpg",
        s"outside/child/${location.value}.tmp",
        "outside/child",
        "outside"
      )
      artifacts.traverse_ { name =>
        IO.blocking(Files.deleteIfExists(root.resolve(name))).void
      } >> IO.blocking(Files.deleteIfExists(root)).void
