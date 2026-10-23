package photography.usecases

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.domain.layout.*
import photography.ports.SpreadRepository
import photography.ports.SpreadRepository.Failure
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

import java.util.UUID

class SpreadTemplateServiceComponentTest extends CatsEffectSuite:

  test("should list only AlbumStart templates by lowest ID and report failure when the repository cannot list them"):
    val listed = Vector(
      buildTemplate(21, SpreadTemplateKind.AlbumStart),
      buildTemplate(22, SpreadTemplateKind.PhotosOnly),
      buildTemplate(20, SpreadTemplateKind.AlbumStart)
    )
    val expectedTemplates = Vector(buildTemplate(20, SpreadTemplateKind.AlbumStart), buildTemplate(21, SpreadTemplateKind.AlbumStart))
    val expected          = ListAlbumStartTemplatesResult.Listed(expectedTemplates)
    val failedRepository  = SpreadRepository.ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure)

    for
      found  <- buildService(SpreadRepository.ListAlbumStartTemplatesResult.Listed(listed)).listAlbumStartTemplates
      failed <- buildService(failedRepository).listAlbumStartTemplates
    yield
      assertEquals(found, expected)
      assertEquals(failed, ListAlbumStartTemplatesResult.Failed)

  private def buildService(listResult: SpreadRepository.ListAlbumStartTemplatesResult): SpreadTemplateService[IO] =
    val repository = new SpreadRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadRepository.ListAlbumStartTemplatesResult] = listResult.pure[IO]
    SpreadTemplateService.make(repository)

  private def buildTemplate(index: Int, kind: SpreadTemplateKind) =
    SpreadTemplate(
      SpreadTemplateId(new UUID(0L, index.toLong)),
      s"Template $index".refineUnsafe[Not[Empty]],
      kind,
      NonEmptyList.one(SpreadTemplateSlot.Text(SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)))
    )
