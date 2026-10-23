package photography.adapters.sqlite

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.app.SqliteConfig
import photography.app.album.{AlbumRepositoryFailure, AlbumService, UtcClock, UUIDGenerator}
import photography.domain.layout.{SpreadTemplateKind, SpreadTemplateSlot}

import java.nio.file.Files
import java.time.Instant
import java.util.UUID

final class AlbumSchemaSeamIntegrationTest extends CatsEffectSuite:

  private val createdAt = Instant.parse("2026-10-07T12:00:00Z")

  test("should expose the seeded AlbumStart template and persist Album creation time"):
    buildMigratedDatabase.use: transactor =>
      val repository = SqliteAlbumRepository.make[IO](transactor)

      for
        nextId <- Ref.of[IO, Int](1)
        identifiers = new UUIDGenerator[IO]:
          override def nextUUID: IO[UUID] =
            nextId.getAndUpdate(_ + 1).map(buildUuid)
        clock = new UtcClock[IO]:
          override def now: IO[Instant] = IO.pure(createdAt)
        service = AlbumService.make(repository, identifiers, clock)
        template <- repository.builtInAlbumStartTemplate
        created  <- service.create("Schema-backed album")
        album = created.fold(failure => fail(s"expected Album creation, got $failure"), identity)
        loaded <- repository.getAlbum(album.id)
      yield
        val loadedTemplate = template.fold(failure => fail(s"expected template, got $failure"), identity)
          .fold(fail("expected the seeded AlbumStart template"))(identity)
        assertEquals(loadedTemplate.kind, SpreadTemplateKind.AlbumStart)
        assertEquals(loadedTemplate.slots.toSortedSet.toVector, Vector(SpreadTemplateSlot.Text(buildFullSpreadBox)))
        assertEquals(album.createdAt, createdAt)
        assertEquals(loaded, album.some.asRight[AlbumRepositoryFailure])

  private def buildFullSpreadBox =
    val x      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val y      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val width  = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    val height = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    photography.domain.layout.SlotBox(x, y, width, height)

  private def buildUuid(index: Int) =
    val suffix = index.toString.reverse.padTo(12, '0').reverse
    UUID.fromString(s"00000000-0000-4000-8000-$suffix")

  private def buildMigratedDatabase =
    for
      path <- Resource.make(
        IO.blocking(Files.createTempFile("photography-album-schema", ".sqlite"))
      )(path => IO.blocking(Files.deleteIfExists(path)).void)
      transactor <- SqliteConnection.make[IO](SqliteConfig(path, 1, 1)).transactor
      flyway     <- FlywayMigrator.make[IO](path)
      _          <- Resource.eval(flyway.migrate)
    yield transactor
