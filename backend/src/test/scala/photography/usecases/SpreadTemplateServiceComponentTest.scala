package photography.usecases

import cats.data.NonEmptyList
import cats.effect.IO
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.domain.layout.*
import photography.ports.SpreadRepository
import photography.ports.SpreadRepository.SpreadRepositoryFailure
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

import java.util.UUID

class SpreadTemplateServiceComponentTest extends CatsEffectSuite:

  test("should list only AlbumStart templates ordered by lowest ID"):
    val listed = Vector(
      buildTemplate(21, SpreadTemplateKind.AlbumStart),
      buildTemplate(22, SpreadTemplateKind.PhotosOnly),
      buildTemplate(20, SpreadTemplateKind.AlbumStart)
    )
    val repository = new SpreadRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadRepository.ListAlbumStartTemplatesResult] =
        IO.pure(SpreadRepository.ListAlbumStartTemplatesResult.Listed(listed))

    val expectedTemplates = Vector(buildTemplate(20, SpreadTemplateKind.AlbumStart), buildTemplate(21, SpreadTemplateKind.AlbumStart))
    val expected          = ListAlbumStartTemplatesResult.Listed(expectedTemplates)

    SpreadTemplateService.make(repository).listAlbumStartTemplates.map(assertEquals(_, expected))

  test("should report failure when the repository cannot list templates"):
    val repository = new SpreadRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadRepository.ListAlbumStartTemplatesResult] =
        IO.pure(SpreadRepository.ListAlbumStartTemplatesResult.Failed(SpreadRepositoryFailure.DatabaseFailure))

    SpreadTemplateService.make(repository).listAlbumStartTemplates.map(assertEquals(_, ListAlbumStartTemplatesResult.Failed))

  private def buildTemplate(index: Int, kind: SpreadTemplateKind) =
    SpreadTemplate(
      SpreadTemplateId(new UUID(0L, index.toLong)),
      s"Template $index".refineUnsafe[Not[Empty]],
      kind,
      NonEmptyList.one(SpreadTemplateSlot.Text(SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)))
    )
