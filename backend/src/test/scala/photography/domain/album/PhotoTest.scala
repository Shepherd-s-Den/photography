package photography.domain.album

import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import java.util.UUID

final class PhotoTest extends FunSuite:
  test("should construct complete thumbnails only within the selected quality's byte and pixel ceilings"):
    ThumbnailQuality.values.toVector.foreach { quality =>
      val location   = ThumbnailLocation(new UUID(0L, 1L))
      val dimensions = PhotoDimensions(quality.longestEdge, height = 1)
      val content    = PhotoContent(bytes = Vector.fill(quality.maximumBytes)(1.toByte))
      val oversized  = PhotoDimensions(width = 4096, height = 1)

      val encoded = EncodedThumbnail.from(content, dimensions, quality)
      val stored  = Thumbnail.from(location, version = "v1", dimensions, quality, bytes = quality.maximumBytes)
      val actual  = stored.map(value => (value.location.value, value.version: String, value.dimensions, value.quality, value.bytes))

      assertEquals(encoded.map(_.content), content.some)
      assertEquals(encoded.map(_.dimensions), dimensions.some)
      assertEquals(encoded.map(_.quality), quality.some)
      assertEquals(actual, (location.value, "v1", dimensions, quality, quality.maximumBytes).some)
      Vector(0, quality.maximumBytes + 1).foreach { bytes =>
        assertEquals(EncodedThumbnail.from(PhotoContent(bytes = Vector.fill(bytes)(1.toByte)), dimensions, quality), None)
        assertEquals(Thumbnail.from(location, version = "v1", dimensions, quality, bytes), None)
      }
      assertEquals(EncodedThumbnail.from(content, oversized, quality), None)
      assertEquals(Thumbnail.from(location, version = "v1", oversized, quality, bytes = 1), None)
      val tooTall = oversized.copy(width = 1, height = 4096)
      assertEquals(EncodedThumbnail.from(content, tooTall, quality), None)
      assertEquals(Thumbnail.from(location, version = "v1", tooTall, quality, bytes = 1), None)
    }
