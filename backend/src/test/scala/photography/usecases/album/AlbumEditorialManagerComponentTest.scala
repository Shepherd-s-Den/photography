package photography.usecases.album

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.collection.MinLength
import munit.CatsEffectSuite
import photography.domain.album.*
import photography.ports.album.AlbumEditorialRepository
import photography.usecases.album.AlbumEditorialManager.*
import java.util.UUID

final class AlbumEditorialManagerComponentTest extends CatsEffectSuite:
  private val album = AlbumId(new UUID(0L, 1L))
  private val all   = Set(Author.Cosmin, Author.Mara, Author.Iacob).refineUnsafe[MinLength[1]]
  private val pair  = Set(Author.Cosmin, Author.Mara).refineUnsafe[MinLength[1]]
  private val saved = AlbumEditorial(synopsis = "Început\n日本", authors = pair)

  test("should initialize new albums independently of year and retain historical attribution"):
    val manager = buildManager(Refs())
    val cases   = Vector(
      Origin.NewAlbum                   -> all,
      Origin.ExistingAlbum(year = 1900) -> pair,
      Origin.ExistingAlbum(year = 2025) -> pair,
      Origin.ExistingAlbum(year = 2026) -> all,
      Origin.ExistingAlbum(year = 2100) -> all
    )

    val actual = cases.map { case (origin, _) => manager.initialize(origin) }

    assertEquals(actual, cases.map { case (_, authors) => InitializationResult.Initialized(authors) })
    assertEquals(Author.values.toVector, Vector(Author.Cosmin, Author.Mara, Author.Iacob))

  test("should save every nonempty author combination once without changing synopsis or reading back"):
    val refs         = Refs()
    val combinations = all.subsets().filter(_.nonEmpty).toVector.map(_.refineUnsafe[MinLength[1]])
    val manager      = buildManager(refs)

    for
      results <- combinations.traverse(manager.saveAuthors(album, _))
      writes  <- refs.authors.get
      reads   <- refs.reads.get
      text    <- refs.synopses.get
    yield
      assertEquals(results, combinations.map(_ => SaveAuthorsResult.Saved))
      assertEquals(writes, combinations)
      assertEquals(reads, 0)
      assertEquals(text, Vector.empty[String])
      assert(Set.empty[Author].refineEither[MinLength[1]].isLeft)

  test("should preserve exact synopsis text and clearing without mutating attribution"):
    val refs    = Refs()
    val manager = buildManager(refs)
    val texts   = Vector(saved.synopsis, "")

    for
      results <- texts.traverse(manager.saveSynopsis(album, _))
      writes  <- refs.synopses.get
      authors <- refs.authors.get
    yield
      assertEquals(results, texts.map(_ => SaveSynopsisResult.Saved))
      assertEquals(writes, texts)
      assertEquals(authors, Vector.empty[AlbumAuthors])

  test("should expose authoritative read outcomes without converting failures into empty content"):
    val cases = Vector(
      AlbumEditorialRepository.ReadResult.Found(saved) -> ReadResult.Found(saved),
      AlbumEditorialRepository.ReadResult.NotFound     -> ReadResult.NotFound,
      AlbumEditorialRepository.ReadResult.Failed       -> ReadResult.Failed
    )

    cases.traverse_ { case (response, expected) =>
      buildManager(Refs(), readResult = response).read(album).map(assertEquals(_, expected))
    }

  test("should distinguish missing, failed and uncertain mutations without replaying either write"):
    val cases = Vector(
      (AlbumEditorialRepository.WriteResult.NotFound, SaveSynopsisResult.NotFound, SaveAuthorsResult.NotFound),
      (AlbumEditorialRepository.WriteResult.Failed, SaveSynopsisResult.Failed, SaveAuthorsResult.Failed),
      (AlbumEditorialRepository.WriteResult.Uncertain, SaveSynopsisResult.Uncertain, SaveAuthorsResult.Uncertain)
    )

    cases.traverse_ { case (response, expectedSynopsis, expectedAuthors) =>
      val refs    = Refs()
      val manager = buildManager(refs, writeResult = response)
      for
        synopsis     <- manager.saveSynopsis(album, saved.synopsis)
        authors      <- manager.saveAuthors(album, pair)
        textWrites   <- refs.synopses.get
        authorWrites <- refs.authors.get
        reads        <- refs.reads.get
      yield
        assertEquals(synopsis, expectedSynopsis)
        assertEquals(authors, expectedAuthors)
        assertEquals(textWrites, Vector(saved.synopsis))
        assertEquals(authorWrites, Vector(pair))
        assertEquals(reads, 0)
    }

  test("should permit authoritative read recovery after a saved synopsis without replaying the successful mutation"):
    val refs    = Refs()
    val manager = buildManager(refs, readResult = AlbumEditorialRepository.ReadResult.Failed)

    for
      savedResult <- manager.saveSynopsis(album, saved.synopsis)
      failedRead  <- manager.read(album)
      retryRead   <- manager.read(album)
      writes      <- refs.synopses.get
      reads       <- refs.reads.get
    yield
      assertEquals(savedResult, SaveSynopsisResult.Saved)
      assertEquals(Vector(failedRead, retryRead), Vector(ReadResult.Failed, ReadResult.Failed))
      assertEquals(writes, Vector(saved.synopsis))
      assertEquals(reads, 2)

  final private case class Refs():
    val reads: Ref[IO, Int]                    = Ref.unsafe(0)
    val synopses: Ref[IO, Vector[String]]      = Ref.unsafe(Vector.empty)
    val authors: Ref[IO, Vector[AlbumAuthors]] = Ref.unsafe(Vector.empty)

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      refs: Refs,
      readResult: AlbumEditorialRepository.ReadResult = AlbumEditorialRepository.ReadResult.Found(saved),
      writeResult: AlbumEditorialRepository.WriteResult = AlbumEditorialRepository.WriteResult.Saved
  ) =
    val repository = new AlbumEditorialRepository[IO]:
      override def read(id: AlbumId): IO[AlbumEditorialRepository.ReadResult] =
        IO(assertEquals(id, album)) >> refs.reads.update(_ + 1).as(readResult)
      override def saveSynopsis(id: AlbumId, text: String): IO[AlbumEditorialRepository.WriteResult] =
        IO(assertEquals(id, album)) >> refs.synopses.update(_ :+ text).as(writeResult)
      override def saveAuthors(id: AlbumId, authors: AlbumAuthors): IO[AlbumEditorialRepository.WriteResult] =
        IO(assertEquals(id, album)) >> refs.authors.update(_ :+ authors).as(writeResult)
    AlbumEditorialManager.make(repository)
  // scalafix:on DisableSyntax.defaultArgs
