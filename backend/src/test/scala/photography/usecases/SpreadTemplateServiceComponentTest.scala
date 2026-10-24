package photography.usecases

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.domain.layout.*
import photography.ports.SpreadTemplateRepository
import photography.ports.SpreadTemplateRepository.Failure
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

import java.util.UUID

final class SpreadTemplateServiceComponentTest extends CatsEffectSuite:

  test("should pass AlbumStart templates through from the repository"):
    val listed = NonEmptyList.of(buildTemplate(20), buildTemplate(21))

    buildService(SpreadTemplateRepository.ListAlbumStartTemplatesResult.Listed(listed)).listAlbumStartTemplates
      .map(found => assertEquals(found, ListAlbumStartTemplatesResult.Listed(listed)))

  test("should report failure when the repository cannot list AlbumStart templates"):
    val failedResult = SpreadTemplateRepository.ListAlbumStartTemplatesResult.Failed(Failure.DatabaseFailure)

    buildService(failedResult).listAlbumStartTemplates.map(failed => assertEquals(failed, ListAlbumStartTemplatesResult.Failed))

  private def buildService(listResult: SpreadTemplateRepository.ListAlbumStartTemplatesResult) =
    val repository = new SpreadTemplateRepository[IO]:
      override def listAlbumStartTemplates: IO[SpreadTemplateRepository.ListAlbumStartTemplatesResult] = listResult.pure[IO]
    SpreadTemplateService.make(repository)

  private def buildTemplate(index: Int) =
    SpreadTemplate(
      SpreadTemplateId(UUID.fromString(s"00000000-0000-4000-8000-${index.toString.reverse.padTo(12, '0').reverse}")),
      s"Template $index".refineUnsafe[Not[Empty]],
      SpreadTemplateKind.AlbumStart,
      NonEmptyList.one(SpreadTemplateSlot.Text(SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)))
    )
