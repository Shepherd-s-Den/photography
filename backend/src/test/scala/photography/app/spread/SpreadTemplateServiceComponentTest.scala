package photography.app.spread

import cats.data.NonEmptySet
import cats.effect.IO
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.CatsEffectSuite
import photography.domain.layout.*

import java.util.UUID

class SpreadTemplateServiceComponentTest extends CatsEffectSuite:

  test("should list only AlbumStart templates ordered by lowest ID"):
    val listed = Vector(
      buildTemplate(21, SpreadTemplateKind.AlbumStart),
      buildTemplate(22, SpreadTemplateKind.PhotosOnly),
      buildTemplate(20, SpreadTemplateKind.AlbumStart)
    )
    val service = SpreadTemplateService.make(SpreadTemplateCatalog(SpreadRepository.ListAlbumStartTemplatesResult.Listed(listed)))

    for result <- service.listAlbumStartTemplates
    yield assertEquals(
      result,
      ListAlbumStartTemplatesResult.Listed(Vector(buildTemplate(20, SpreadTemplateKind.AlbumStart), buildTemplate(21, SpreadTemplateKind.AlbumStart)))
    )

  test("should report failure when the repository cannot list templates"):
    val service = SpreadTemplateService.make(
      SpreadTemplateCatalog(SpreadRepository.ListAlbumStartTemplatesResult.Failed(SpreadRepositoryFailure.DatabaseFailure))
    )

    for result <- service.listAlbumStartTemplates
    yield assertEquals(result, ListAlbumStartTemplatesResult.Failed)

  private def buildTemplate(index: Int, kind: SpreadTemplateKind): SpreadTemplate =
    SpreadTemplate(
      SpreadTemplateId(new UUID(0L, index.toLong)),
      s"Template $index".refineUnsafe[Not[Empty]],
      kind,
      NonEmptySet.one(SpreadTemplateSlot.Text(SlotBox(0.0.refineUnsafe, 0.0.refineUnsafe, 1.0.refineUnsafe, 1.0.refineUnsafe)))
    )

  final private case class SpreadTemplateCatalog(listResult: SpreadRepository.ListAlbumStartTemplatesResult) extends SpreadRepository[IO]:
    override def listAlbumStartTemplates: IO[SpreadRepository.ListAlbumStartTemplatesResult] = IO.pure(listResult)
