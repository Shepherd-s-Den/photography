package photography.adapters.http

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import org.http4s.{HttpRoutes, MessageBodyFailure}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import photography.domain.album.{AlbumId, ChapterId}
import photography.domain.layout.SpreadId
import photography.usecases.chapter.ChapterComposition
import java.util.UUID

object ChapterCompositionApi:
  def make[F[_]: Async](composition: ChapterComposition[F]): HttpRoutes[F] = new LiveChapterCompositionApi(composition).routes

  final private class LiveChapterCompositionApi[F[_]: Async](composition: ChapterComposition[F]) extends Http4sDsl[F]:
    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
    private given Decoder[SpreadId] =
      Decoder.decodeString.emap(value => CanonicalId.unapply(value).map(SpreadId(_)).toRight("Use canonical spread IDs"))
    private given Decoder[NonEmptyList[SpreadId]] =
      Decoder.decodeList[SpreadId].emap(values => NonEmptyList.fromList(values).toRight("Supply an opening and spread order"))
    val routes: HttpRoutes[F] = HttpRoutes.of[F] {
      case request @ PUT -> Root / "api" / "albums" / CanonicalId(album) / "chapters" / CanonicalId(chapter) / "composition" =>
        request.as[Json].map(value =>
          (value.hcursor.get[NonEmptyList[SpreadId]]("expectedSpreadIds"), value.hcursor.get[NonEmptyList[SpreadId]]("spreadIds")).tupled
            .leftMap(_.message)
        ).recover { case _: MessageBodyFailure => "Supply a valid composition".asLeft[(NonEmptyList[SpreadId], NonEmptyList[SpreadId])] }.flatMap:
          case Left(message)            => BadRequest(Map("message" -> message))
          case Right((expected, order)) =>
            composition.replace(AlbumId(album), ChapterId(chapter), expected, order).flatMap:
              case ChapterComposition.Result.Saved     => NoContent()
              case ChapterComposition.Result.Invalid   => BadRequest(Map("message" -> "Retain the opening and every spread exactly once"))
              case ChapterComposition.Result.NotFound  => NotFound(Map("code" -> "chapter_not_found"))
              case ChapterComposition.Result.Stale     => Conflict(Map("code" -> "composition_changed"))
              case ChapterComposition.Result.Uncertain => InternalServerError(Map("code" -> "write_uncertain"))
    }
