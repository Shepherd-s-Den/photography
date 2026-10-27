package photography.domain.album

import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.FunSuite

final class PhotoTest extends FunSuite:
  test("should restrict thumbnail measurements and encoded content to positive bounded values"):
    Vector(0, 1, 2048, 2049, 300000, 300001).foreach { value =>
      val pixels = value.refineEither[ThumbnailPixelCountConstraint]
      val bytes  = value.refineEither[ThumbnailByteCountConstraint]
      val image  = Vector.fill(value)(1.toByte).refineEither[ThumbnailBytesConstraint]

      assertEquals(pixels.isRight, value >= 1 && value <= 2048)
      assertEquals(bytes.isRight, value >= 1 && value <= 300000)
      assertEquals(image.isRight, bytes.isRight)
    }

  test("should represent original filenames without paths or parent-directory references"):
    Vector("one.JPG", "", " ", ".", "..", "../one.jpg", "trip/one.jpg", "trip\\one.jpg", "one\u0000.jpg", "drive:one.jpg").foreach { value =>
      val filename = value.refineEither[PhotoFilenameConstraint]

      assertEquals(filename.isRight, value === "one.JPG")
    }
