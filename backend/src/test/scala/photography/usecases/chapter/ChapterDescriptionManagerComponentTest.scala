package photography.usecases.chapter

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import munit.CatsEffectSuite
import photography.domain.album.{AlbumId, ChapterId}
import photography.ports.chapter.ChapterDescriptionRepository
import photography.usecases.chapter.ChapterDescriptionManager.*
import java.util.UUID

final class ChapterDescriptionManagerComponentTest extends CatsEffectSuite:
  private val album   = AlbumId(new UUID(0L, 1L))
  private val chapter = ChapterId(new UUID(0L, 2L))

  test("should read exact chapter text and preserve missing and failed reads"):
    val cases = Vector(
      ChapterDescriptionRepository.ReadResult.Found("日本\nVacanță") -> ReadResult.Found("日本\nVacanță"),
      ChapterDescriptionRepository.ReadResult.NotFound             -> ReadResult.NotFound,
      ChapterDescriptionRepository.ReadResult.Failed               -> ReadResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), readResult = response).read(album, chapter).map(assertEquals(_, expected))
    }

  test("should save or clear only the scoped description once and preserve uncertain or rejected outcomes"):
    val cases = Vector(
      ChapterDescriptionRepository.WriteResult.Saved     -> SaveResult.Saved,
      ChapterDescriptionRepository.WriteResult.NotFound  -> SaveResult.NotFound,
      ChapterDescriptionRepository.WriteResult.Failed    -> SaveResult.Failed,
      ChapterDescriptionRepository.WriteResult.Uncertain -> SaveResult.Uncertain
    )

    cases.traverse_ { case (response, expected) =>
      val refs    = Refs()
      val manager = buildManager(refs, writeResult = response)
      val texts   = Vector("日本\nVacanță", "")
      for
        results <- texts.traverse(manager.save(album, chapter, _))
        writes  <- refs.writes.get
        reads   <- refs.reads.get
      yield
        assertEquals(results, texts.map(_ => expected))
        assertEquals(writes, texts)
        assertEquals(reads, 0)
    }

  final private case class Refs():
    val writes: Ref[IO, Vector[String]] = Ref.unsafe(Vector.empty)
    val reads: Ref[IO, Int]             = Ref.unsafe(0)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      readResult: ChapterDescriptionRepository.ReadResult = ChapterDescriptionRepository.ReadResult.NotFound,
      writeResult: ChapterDescriptionRepository.WriteResult = ChapterDescriptionRepository.WriteResult.Saved
  ) =
    val repository = new ChapterDescriptionRepository[IO]:
      override def read(owner: AlbumId, id: ChapterId): IO[ChapterDescriptionRepository.ReadResult] =
        IO(assertEquals((owner, id), (album, chapter))) >> refs.reads.update(_ + 1).as(readResult)
      override def save(owner: AlbumId, id: ChapterId, text: String): IO[ChapterDescriptionRepository.WriteResult] =
        IO(assertEquals((owner, id), (album, chapter))) >> refs.writes.update(_ :+ text).as(writeResult)
    ChapterDescriptionManager.make(repository)
  // scalafix:on DisableSyntax.defaultArgs
