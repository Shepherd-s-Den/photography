package photography.usecases

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.CatsEffectSuite
import photography.domain.layout.*
import photography.ports.SpreadTemplateRepository
import photography.ports.SpreadTemplateRepository.Failure
import photography.usecases.SpreadTemplateManager.*

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
  private val photoTemplate =
    SpreadTemplate.PhotosOnly(SpreadTemplateId(new UUID(0L, 30L)), name = "Single", photoSlots = NonEmptyList.one(box))

  test("should preserve the complete ordered context-specific template catalogs"):
    val manager = buildManager(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(albumTemplates),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(chapterTemplates),
      photos = SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(NonEmptyList.one(photoTemplate))
    )

    val listed = (manager.listAlbumStartTemplates, manager.listChapterStartTemplates, manager.listPhotosOnlyTemplates).tupled

    listed.map { case (albums, chapters, photos) =>
      assertEquals(albums, ListAlbumStartTemplatesResult.Listed(albumTemplates))
      assertEquals(chapters, ListChapterStartTemplatesResult.Listed(chapterTemplates))
      assertEquals(photos, ListPhotosOnlyTemplatesResult.Listed(NonEmptyList.one(photoTemplate)))
    }

  test("should report unavailable context-specific template catalogs explicitly"):
    val manager = buildManager(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure)
    )

    val listed = (manager.listAlbumStartTemplates, manager.listChapterStartTemplates, manager.listPhotosOnlyTemplates).tupled

    listed.map { case (albums, chapters, photos) =>
      assertEquals(albums, ListAlbumStartTemplatesResult.Failed)
      assertEquals(chapters, ListChapterStartTemplatesResult.Failed)
      assertEquals(photos, ListPhotosOnlyTemplatesResult.Failed)
    }

  test(s"should resolve ${photoTemplate.id.value} and distinguish an unknown identity from a failed lookup"):
    Vector(
      SpreadTemplateRepository.FindTemplateResult.Found(photoTemplate)            -> FindTemplateResult.Found(photoTemplate),
      SpreadTemplateRepository.FindTemplateResult.NotFound                        -> FindTemplateResult.NotFound,
      SpreadTemplateRepository.FindTemplateResult.Failed(Failure.DatabaseFailure) -> FindTemplateResult.Failed
    ).traverse_ { case (outcome, expected) =>
      val manager = buildManager(
        SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure),
        SpreadTemplateRepository.ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure),
        lookup = outcome
      )

      manager.findTemplate(photoTemplate.id).map(result => assertEquals(result, expected))
    }

  // scalafix:off DisableSyntax.defaultArgs
  private def buildManager(
      albums: SpreadTemplateRepository.ListAlbumStartTemplatesResult,
      chapters: SpreadTemplateRepository.ListChapterStartTemplatesResult,
      photos: SpreadTemplateRepository.ListPhotosOnlyTemplatesResult = SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Failed(
        SpreadTemplateRepository.Failure.DatabaseFailure
      ),
      lookup: SpreadTemplateRepository.FindTemplateResult = SpreadTemplateRepository.FindTemplateResult.NotFound
  ) =
    val repository = new SpreadTemplateRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateRepository.ListAlbumStartTemplatesResult]     = albums.pure[IO]
      override def listChapterStartTemplates: IO[SpreadTemplateRepository.ListChapterStartTemplatesResult] = chapters.pure[IO]
      override def listPhotosOnlyTemplates: IO[SpreadTemplateRepository.ListPhotosOnlyTemplatesResult]     = photos.pure[IO]
      override def findTemplate(id: SpreadTemplateId): IO[SpreadTemplateRepository.FindTemplateResult]     = lookup.pure[IO]
    SpreadTemplateManager.make(repository)
  // scalafix:on DisableSyntax.defaultArgs
