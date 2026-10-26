package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import io.circe.Json
import io.circe.jawn.parse
import io.github.iltotore.iron.*
import munit.CatsEffectSuite
import org.http4s.{MediaType, Method, Request, Status, Uri}
import org.http4s.circe.CirceEntityCodec.*
import photography.domain.layout.*
import photography.usecases.SpreadTemplateManager
import photography.usecases.SpreadTemplateManager.ListAlbumStartTemplatesResult

import java.util.UUID

final class SpreadTemplateApiSeamIntegrationTest extends CatsEffectSuite:
  private val photoBox      = SlotBox(x = 0.0.refineUnsafe, y = 0.0.refineUnsafe, width = 21.0.refineUnsafe, height = 28.0.refineUnsafe)
  private val synopsisBox   = SlotBox(x = 21.0.refineUnsafe, y = 0.0.refineUnsafe, width = 21.0.refineUnsafe, height = 28.0.refineUnsafe)
  private val firstTemplate =
    SpreadTemplate.AlbumStart(SpreadTemplateId(new UUID(0L, 2L)), name = "Synopsis right".refineUnsafe, Vector(photoBox), synopsisBox)
  private val secondTemplate = firstTemplate.copy(id = SpreadTemplateId(new UUID(0L, 4L)), name = "Second".refineUnsafe)
  private val catalog        = NonEmptyList.of(firstTemplate, secondTemplate)

  test("should list album opening templates with ordered geometry and report catalog failures"):
    val server            = buildSpreadTemplateApi(ListAlbumStartTemplatesResult.Listed(catalog))
    val unavailableServer = buildSpreadTemplateApi(ListAlbumStartTemplatesResult.Failed)
    val request           = Request[IO](Method.GET, Uri.unsafeFromString("/api/templates/album-start"))
    val expectedPhoto     = """{"kind":"photo","x":0.0,"y":0.0,"width":21.0,"height":28.0}"""
    val expectedText      = """{"kind":"text","x":21.0,"y":0.0,"width":21.0,"height":28.0}"""
    val expectedFirst     = s"""{"id":"${firstTemplate.id.value}","name":"Synopsis right","slots":[$expectedPhoto,$expectedText]}"""
    val expectedSecond    = s"""{"id":"${secondTemplate.id.value}","name":"Second","slots":[$expectedPhoto,$expectedText]}"""
    val expectedCatalog   = parse(s"""{"templates":[$expectedFirst,$expectedSecond]}""").fold(error => fail(error.message), identity)
    val expectedFailure   = Json.obj("code" -> Json.fromString("service_failure"))

    for
      listed      <- server.run(request)
      catalogBody <- listed.as[Json]
      unavailable <- unavailableServer.run(request)
      failureBody <- unavailable.as[Json]
    yield
      assertEquals(listed.status -> catalogBody, Status.Ok -> expectedCatalog)
      assertEquals(listed.contentType.map(_.mediaType), MediaType.application.json.some)
      assertEquals(unavailable.status -> failureBody, Status.InternalServerError -> expectedFailure)
      assertEquals(unavailable.contentType.map(_.mediaType), MediaType.application.json.some)

  private def buildSpreadTemplateApi(result: ListAlbumStartTemplatesResult) =
    val templates = new SpreadTemplateManager[IO]:
      override def listAlbumStartTemplates: IO[ListAlbumStartTemplatesResult]                           = result.pure[IO]
      override def listChapterStartTemplates: IO[SpreadTemplateManager.ListChapterStartTemplatesResult] =
        SpreadTemplateManager.ListChapterStartTemplatesResult.Failed.pure[IO]
    SpreadTemplateApi.make(templates).routes.orNotFound
