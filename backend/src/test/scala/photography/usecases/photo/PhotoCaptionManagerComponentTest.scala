package photography.usecases.photo

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import photography.domain.album.PhotoId
import photography.ports.photo.PhotoCaptionRepository
import photography.usecases.photo.PhotoCaptionManager.*
import java.util.UUID

final class PhotoCaptionManagerComponentTest extends CatsEffectSuite:
  private val photo = PhotoId(new UUID(0L, 1L))

  test("should distinguish absent captions from failed or missing authoritative reads"):
    val cases = Vector(
      PhotoCaptionRepository.ReadResult.Found(maybeCaption = "日本\nVacanță".some) -> ReadResult.Found(maybeCaption = "日本\nVacanță".some),
      PhotoCaptionRepository.ReadResult.Found(maybeCaption = None)               -> ReadResult.Found(maybeCaption = None),
      PhotoCaptionRepository.ReadResult.NotFound                                 -> ReadResult.NotFound,
      PhotoCaptionRepository.ReadResult.Failed                                   -> ReadResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), readResult = response).read(photo).map(assertEquals(_, expected))
    }

  test("should save exact caption text or clear the label once without reading or replaying writes"):
    val cases = Vector(
      PhotoCaptionRepository.WriteResult.Saved     -> SaveResult.Saved,
      PhotoCaptionRepository.WriteResult.NotFound  -> SaveResult.NotFound,
      PhotoCaptionRepository.WriteResult.Failed    -> SaveResult.Failed,
      PhotoCaptionRepository.WriteResult.Uncertain -> SaveResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs    = Refs()
      val manager = buildManager(refs, writeResult = response)

      for
        text    <- manager.save(photo, text = "日本\nVacanță")
        cleared <- manager.save(photo, text = "")
        writes  <- refs.writes.get
        reads   <- refs.reads.get
      yield
        assertEquals(Vector(text, cleared), Vector(expected, expected))
        assertEquals(writes, Vector("日本\nVacanță".some, None))
        assertEquals(reads, 0)
    }

  final private case class Refs():
    val writes: Ref[IO, Vector[Option[String]]] = Ref.unsafe(Vector.empty)
    val reads: Ref[IO, Int]                     = Ref.unsafe(0)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      readResult: PhotoCaptionRepository.ReadResult = PhotoCaptionRepository.ReadResult.NotFound,
      writeResult: PhotoCaptionRepository.WriteResult = PhotoCaptionRepository.WriteResult.Saved
  ) =
    val repository = new PhotoCaptionRepository[IO]:
      override def read(id: PhotoId): IO[PhotoCaptionRepository.ReadResult] =
        IO(assertEquals(id, photo)) >> refs.reads.update(_ + 1).as(readResult)
      override def save(id: PhotoId, maybeCaption: Option[String]): IO[PhotoCaptionRepository.WriteResult] =
        IO(assertEquals(id, photo)) >> refs.writes.update(_ :+ maybeCaption).as(writeResult)
    PhotoCaptionManager.make(repository)
  // scalafix:on DisableSyntax.defaultArgs
