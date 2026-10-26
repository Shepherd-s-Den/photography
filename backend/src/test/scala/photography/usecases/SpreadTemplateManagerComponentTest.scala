package photography.usecases

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.domain.layout.*
import photography.ports.SpreadTemplateRepository
import photography.ports.SpreadTemplateRepository.Failure
import photography.usecases.SpreadTemplateManager.{ListAlbumStartTemplatesResult, ListChapterStartTemplatesResult}

import java.util.UUID

final class SpreadTemplateManagerComponentTest extends CatsEffectSuite:
  private val box            = SlotBox(x = 0.0, y = 0.0, width = 50.0, height = 100.0)
  private val albumTemplates = NonEmptyList.of(
    SpreadTemplate.AlbumStart(SpreadTemplateId(new UUID(0L, 20L)), name = "Album first", photoSlots = Vector(box), textSlot = box.copy(x = 50.0)),
    SpreadTemplate.AlbumStart(SpreadTemplateId(new UUID(0L, 21L)), name = "Album second", photoSlots = Vector.empty[SlotBox], textSlot = box)
  )
  private val chapterTemplates = NonEmptyList.of(
    SpreadTemplate.ChapterStart(SpreadTemplateId(new UUID(0L, 22L)), name = "Chapter first", photoSlots = Vector(box), textSlot = box.copy(x = 50.0)),
    SpreadTemplate.ChapterStart(SpreadTemplateId(new UUID(0L, 23L)), name = "Chapter second", photoSlots = Vector.empty[SlotBox], textSlot = box)
  )

  test("should preserve the complete ordered album and chapter template catalogs"):
    val service = buildService(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(albumTemplates),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(chapterTemplates)
    )

    for
      albums   <- service.listAlbumStartTemplates
      chapters <- service.listChapterStartTemplates
    yield
      assertEquals(albums, ListAlbumStartTemplatesResult.Listed(albumTemplates))
      assertEquals(chapters, ListChapterStartTemplatesResult.Listed(chapterTemplates))

  test("should report unavailable album and chapter template catalogs explicitly"):
    val service = buildService(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure)
    )

    for
      albums   <- service.listAlbumStartTemplates
      chapters <- service.listChapterStartTemplates
    yield
      assertEquals(albums, ListAlbumStartTemplatesResult.Failed)
      assertEquals(chapters, ListChapterStartTemplatesResult.Failed)

  private def buildService(
      albums: SpreadTemplateRepository.ListAlbumStartTemplatesResult,
      chapters: SpreadTemplateRepository.ListChapterStartTemplatesResult
  ) =
    val repository = new SpreadTemplateRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateRepository.ListAlbumStartTemplatesResult]     = albums.pure[IO]
      override def listChapterStartTemplates: IO[SpreadTemplateRepository.ListChapterStartTemplatesResult] = chapters.pure[IO]
    SpreadTemplateManager.make(repository)
