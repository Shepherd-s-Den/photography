package photography.adapters.filesystem

import cats.effect.{IO, Resource}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.autoRefine
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import photography.domain.album.{ThumbnailBytesConstraint, ThumbnailLocation}
import photography.usecases.ThumbnailDelivery.GetResult
import java.nio.file.{Files, Path}
import java.util.UUID

final class FileThumbnailDeliverySeamIntegrationTest extends CatsEffectSuite:
  private given Logger[IO]                  = Slf4jLogger.getLogger[IO]
  private val location                      = ThumbnailLocation(new UUID(0L, 1L))
  private val bytes                         = Vector(1.toByte, 2.toByte)
  private val version: String :| Not[Blank] = "a12871fee210fb8619291eaea194581cbd2531e4b23759d225f6806923f63222"

  test("should resolve only the requested managed content version and distinguish absence from read failure"):
    buildDirectory.use: root =>
      val file     = root.resolve(s"${location.value}.jpg")
      val delivery = FileThumbnailDelivery.make[IO](root)

      for
        absent      <- delivery.get(location, version = version)
        _           <- IO.blocking(Files.write(file, bytes.toArray))
        found       <- delivery.get(location, version = version)
        stale       <- delivery.get(location, version = "wrong-version")
        missingRoot <- FileThumbnailDelivery.make[IO](root.resolve("absent")).get(location, version = version)
        invalidRoot <- FileThumbnailDelivery.make[IO](file).get(location, version = version)
        relative    <- FileThumbnailDelivery.make[IO](Path.of("relative")).get(location, version = version)
        _           <- IO.blocking(Files.write(file, Array.emptyByteArray))
        empty       <- delivery.get(location, version = version)
        _           <- IO.blocking(Files.write(file, Array.fill[Byte](300001)(1)))
        excessive   <- delivery.get(location, version = version)
      yield
        assertEquals(found, GetResult.Found(bytes = bytes.refineUnsafe[ThumbnailBytesConstraint]))
        assertEquals(Vector(absent, stale, missingRoot), Vector.fill(3)(GetResult.NotFound))
        assertEquals(Vector(invalidRoot, relative, empty, excessive), Vector.fill(4)(GetResult.Failed))

  test("should reject a managed filename pointing outside its configured root"):
    buildDirectory.use: root =>
      val nested  = root.resolve("managed")
      val outside = root.resolve("original.jpg")
      val link    = nested.resolve(s"${location.value}.jpg")

      for
        _      <- IO.blocking(Files.createDirectory(nested))
        _      <- IO.blocking(Files.write(outside, bytes.toArray))
        _      <- IO.blocking(Files.createSymbolicLink(link, outside))
        result <- FileThumbnailDelivery.make[IO](nested).get(location, version = version)
        _      <- IO.blocking(Files.delete(link))
        _      <- IO.blocking(Files.delete(nested))
        _      <- IO.blocking(Files.delete(outside))
      yield assertEquals(result, GetResult.NotFound)

  private def buildDirectory =
    Resource.make(IO.blocking(Files.createTempDirectory(Path.of("target"), "delivery-").toAbsolutePath)): root =>
      IO.blocking(Files.deleteIfExists(root.resolve(s"${location.value}.jpg"))).void >> IO.blocking(Files.delete(root))
