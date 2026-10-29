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
    val manager = buildManager(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(albumTemplates),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Listed(chapterTemplates)
    )

    val listed = (manager.listAlbumStartTemplates, manager.listChapterStartTemplates).tupled

    listed.map { case (albums, chapters) =>
      assertEquals(albums, ListAlbumStartTemplatesResult.Listed(albumTemplates))
      assertEquals(chapters, ListChapterStartTemplatesResult.Listed(chapterTemplates))
    }

  test("should report unavailable album and chapter template catalogs explicitly"):
    val manager = buildManager(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure)
    )

    val listed = (manager.listAlbumStartTemplates, manager.listChapterStartTemplates).tupled

    listed.map { case (albums, chapters) =>
      assertEquals(albums, ListAlbumStartTemplatesResult.Failed)
      assertEquals(chapters, ListChapterStartTemplatesResult.Failed)
    }

  test("should preserve the ordered photo layout catalog and resolve templates by identity"):
    val photoId = SpreadTemplateId(new UUID(0L, 30L))
    val photo   = SpreadTemplate.PhotosOnly(photoId, name = "Single", photoSlots = NonEmptyList.one(box))
    val manager = buildManager(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Failed(Failure.DatabaseFailure),
      photos = SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Listed(NonEmptyList.one(photo)),
      lookup = SpreadTemplateRepository.FindTemplateResult.Found(photo)
    )

    (manager.listPhotosOnlyTemplates, manager.findTemplate(photoId)).tupled.map { case (listed, found) =>
      assertEquals(listed, SpreadTemplateManager.ListPhotosOnlyTemplatesResult.Listed(NonEmptyList.one(photo)))
      assertEquals(found, SpreadTemplateManager.FindTemplateResult.Found(photo))
    }

  test("should report unavailable photo layouts and unknown template identities explicitly"):
    val photoId = SpreadTemplateId(new UUID(0L, 31L))
    val failed  = SpreadTemplateRepository.Failure.DatabaseFailure
    val manager = buildManager(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(failed),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Failed(failed),
      photos = SpreadTemplateRepository.ListPhotosOnlyTemplatesResult.Failed(failed),
      lookup = SpreadTemplateRepository.FindTemplateResult.NotFound
    )

    (manager.listPhotosOnlyTemplates, manager.findTemplate(photoId)).tupled.map { case (listed, found) =>
      assertEquals(listed, SpreadTemplateManager.ListPhotosOnlyTemplatesResult.Failed)
      assertEquals(found, SpreadTemplateManager.FindTemplateResult.NotFound)
    }

  test("should report template lookup database failures explicitly"):
    val failed  = SpreadTemplateRepository.Failure.DatabaseFailure
    val manager = buildManager(
      SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(failed),
      SpreadTemplateRepository.ListChapterStartTemplatesResult.Failed(failed),
      lookup = SpreadTemplateRepository.FindTemplateResult.Failed(failed)
    )

    manager.findTemplate(SpreadTemplateId(new UUID(0L, 32L))).map(found => assertEquals(found, SpreadTemplateManager.FindTemplateResult.Failed))

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
