package photography.adapters.http

import cats.effect.Async
import cats.syntax.all.*
import io.circe.{Decoder, Encoder, Json}
import io.github.iltotore.iron.*
import org.http4s.{CacheDirective, HttpRoutes, MessageBodyFailure, Query, QueryParamDecoder, QueryParameterValue, Request, ServerSentEvent}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl
import io.github.iltotore.iron.constraint.all.*
import photography.usecases.album.AlbumManager.*
import org.http4s.headers.`Cache-Control`
import photography.domain.album.*
import photography.domain.layout.*
import photography.usecases.album.{AlbumManager, AlbumWorkspaceManager}
import photography.usecases.album.AlbumWorkspaceManager.GetResult
import java.util.UUID
import fs2.Stream
import photography.usecases.photo.AlbumPhotoEvents
import photography.usecases.photo.AlbumPhotoEvents.{Event, SubscribeResult}
import scala.concurrent.duration.*

trait AlbumApi[F[_]]:
  def routes: HttpRoutes[F]

object AlbumApi:
  def make[F[_]: Async](albums: AlbumManager[F], workspaces: AlbumWorkspaceManager[F], events: AlbumPhotoEvents[F]): AlbumApi[F] =
    new LiveAlbumApi(albums, workspaces, events)

  final private class LiveAlbumApi[F[_]: Async](albums: AlbumManager[F], workspaces: AlbumWorkspaceManager[F], events: AlbumPhotoEvents[F])
      extends AlbumApi[F] with Http4sDsl[F]:
    override def routes: HttpRoutes[F] =
      HttpRoutes.of[F] {
        case GET -> Root / "api" / "albums" / CanonicalId(id) / "workspace" =>
          getWorkspace(AlbumId(id))
        case GET -> Root / "api" / "albums" / _ / "workspace"                  => respondAlbumNotFound
        case GET -> Root / "api" / "albums" / CanonicalId(id) / "photo-events" =>
          events.subscribe(AlbumId(id)).flatMap:
            case SubscribeResult.Opened(notifications) =>
              val heartbeat = Stream.awakeEvery[F](15.seconds).as(ServerSentEvent(comment = "heartbeat".some))
              Ok(notifications.map(encodeEvent).merge(heartbeat)).map(_.putHeaders("Cache-Control" -> "no-store", "X-Accel-Buffering" -> "no"))
            case SubscribeResult.AlbumNotFound() => NotFound(Map("code" -> "album_not_found"))
            case SubscribeResult.Failed()        => InternalServerError(Map("code" -> "service_failure"))
        case GET -> Root / "api" / "albums" / _ / "photo-events" => NotFound(Map("code" -> "album_not_found"))

        case request @ GET -> Root / "api" / "albums"                    => list(request.uri.query)
        case request @ POST -> Root / "api" / "albums"                   => create(request)
        case GET -> Root / "api" / "albums" / AlbumIdVar(id)             => get(id)
        case request @ PATCH -> Root / "api" / "albums" / AlbumIdVar(id) => rename(id, request)
        case DELETE -> Root / "api" / "albums" / AlbumIdVar(id)          => delete(id)
        case (GET | PATCH | DELETE) -> Root / "api" / "albums" / _       => respondAlbumNotFound
      }

    private def list(query: Query) = query match
      case Page(page) =>
        albums.list(page = page).flatMap:
          case ListAlbumsResult.Listed(selected) => Ok(selected)
          case ListAlbumsResult.Failed           => respondServiceFailure
      case _ => BadRequest(Map("message" -> "Supply page once as a 32-bit integer"))

    private def create(request: Request[F]) =
      request.as[(AlbumYear, AlbumTitle)].flatMap { case (year, title) => albums.create(year, title) }.flatMap {
        case CreateAlbumResult.Created(album)  => Created(album)
        case CreateAlbumResult.YearAlreadyUsed => BadRequest(Map("message" -> "An album already exists for that year"))
        case CreateAlbumResult.Failed          => respondServiceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidYear) }

    private def get(id: AlbumId) =
      albums.get(id).flatMap:
        case found: GetAlbumResult.Found => Ok(found)
        case GetAlbumResult.NotFound     => respondAlbumNotFound
        case GetAlbumResult.Failed       => respondServiceFailure

    private def rename(id: AlbumId, request: Request[F]) =
      request.as[AlbumTitle].flatMap(title => albums.rename(id, title)).flatMap {
        case RenameResult.Renamed(album) => Ok(album)
        case RenameResult.NotFound       => respondAlbumNotFound
        case RenameResult.Failed         => respondServiceFailure
      }.recoverWith { case _: MessageBodyFailure => BadRequest(invalidTitle) }

    private def delete(id: AlbumId) =
      albums.delete(id).flatMap:
        case DeleteAlbumResult.Deleted  => NoContent()
        case DeleteAlbumResult.NotFound => respondAlbumNotFound
        case DeleteAlbumResult.Failed   => respondServiceFailure

    private def respondAlbumNotFound  = NotFound(Map("code" -> "album_not_found"))
    private def respondServiceFailure = InternalServerError(Map("code" -> "service_failure"))
    private val invalidTitle          = Map("message" -> "Supply a non-blank album title")
    private val invalidYear           = Map("message" -> "Choose a year from 1900 to 2100")

    private object Page:
      def unapply(query: Query): Option[Int] =
        query.pairs.collect { case ("page", value) => value } match
          case Vector()            => 1.some
          case Vector(Some(value)) => QueryParamDecoder[Int].decode(QueryParameterValue(value)).toOption
          case _                   => None

    private object AlbumIdVar:
      def unapply(value: String): Option[AlbumId] =
        UUIDVar.unapply(value).filter(_.toString.equalsIgnoreCase(value)).map(AlbumId(_))

    private given Decoder[AlbumYear] = Decoder.decodeJsonNumber.emap(_.toInt.toRight("Expected an integer year"))
      .emap(_.refineEither[AlbumYearConstraint]).prepare(_.downField("year"))

    private given Decoder[AlbumTitle] = Decoder.decodeString.emap(_.trim.refineEither[Not[Empty] & Not[Blank]]).prepare(_.downField("title"))

    private given Decoder[(AlbumYear, AlbumTitle)] = Decoder[AlbumYear].emap: year =>
      s"Journey of the Shepherds $year".refineEither[Not[Empty] & Not[Blank]].map(title => (year, title))

    private given Encoder[Album] = Encoder.forProduct4("id", "year", "title", "createdAt"): album =>
      (album.id.value.toString, album.year: Int, album.title: String, album.createdAt.toString)

    private given Encoder[GetAlbumResult.Found] = Encoder.forProduct5("id", "year", "title", "synopsis", "createdAt"): found =>
      val album = found.album
      (album.id.value.toString, album.year: Int, album.title: String, album.synopsis, album.createdAt.toString)

    private given Encoder[AlbumPage] = Encoder.forProduct5("albums", "page", "pageSize", "totalAlbums", "totalPages"): page =>
      (page.albums, page.page, page.pageSize, page.totalAlbums, page.totalPages)

    private def getWorkspace(id: AlbumId) =
      workspaces.get(id).flatMap:
        case found: GetResult.Found  => Ok(encodeWorkspace(found)).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-store`)))
        case GetResult.AlbumNotFound => respondAlbumNotFound
        case GetResult.Failed        => respondServiceFailure

    private given Encoder[Country] = Encoder.forProduct3("id", "name", "code"): country =>
      (country.id.value.toString, country.name: String, country.code: String)

    private given Encoder[Chapter] = Encoder.instance: chapter =>
      val destination = chapter.destination
      val end         = chapter.period.firstDay.plusDays((chapter.period.days: Long) - 1L)
      Json.obj(
        "id"           -> Json.fromString(chapter.id.value.toString),
        "kind"         -> Json.fromString(if destination.isHome then "home" else "vacation"),
        "name"         -> Json.fromString(destination.name),
        "countries"    -> Json.arr(destination.countries.toList.map(country => Json.fromString(country.id.value.toString))*),
        "start"        -> Json.fromString(chapter.period.firstDay.toString),
        "end"          -> Json.fromString(end.toString),
        "sourceFolder" -> Json.fromString(chapter.sourceFolder),
        "description"  -> Json.fromString(chapter.description)
      )

    private def encodeSlot(id: UUID, kind: String, box: SlotBox) =
      Json.obj(
        "id"     -> Json.fromString(id.toString),
        "kind"   -> Json.fromString(kind),
        "x"      -> Json.fromDoubleOrNull(box.x),
        "y"      -> Json.fromDoubleOrNull(box.y),
        "width"  -> Json.fromDoubleOrNull(box.width),
        "height" -> Json.fromDoubleOrNull(box.height)
      )

    private def encodeSpread(spread: Spread, maybeChapter: Option[ChapterId]) =
      val (kind, photos, maybeText) = spread match
        case value: Spread.AlbumStart   => ("album_start", value.photoSlots, value.textSlot.some)
        case value: Spread.ChapterStart => ("chapter_start", value.photoSlots, value.textSlot.some)
        case value: Spread.PhotosOnly   => ("photos_only", value.photoSlots.toList.toVector, None)
      val slots = photos.map(slot => encodeSlot(slot.id.value, kind = "photo", slot.box)) ++
        maybeText.toList.toVector.map(slot => encodeSlot(slot.id.value, kind = "text", slot.box))
      Json.obj(
        "id"         -> Json.fromString(spread.id.value.toString),
        "chapter"    -> maybeChapter.fold(Json.Null)(chapter => Json.fromString(chapter.value.toString)),
        "kind"       -> Json.fromString(kind),
        "templateId" -> Json.fromString(spread.template.value.toString),
        "slots"      -> Json.arr(slots*)
      )

    private def encodeWorkspace(found: GetResult.Found) =
      val album          = found.album
      val opening        = encodeSpread(album.opening, maybeChapter = None)
      val chapterSpreads = album.chapters.flatMap: chapter =>
        (Vector(chapter.opening) ++ chapter.spreads).map(spread => encodeSpread(spread, maybeChapter = chapter.id.some))
      Json.obj(
        "year"             -> Json.fromInt(album.year),
        "photoRoot"        -> Json.fromString(found.photoRoot),
        "chapters"         -> Encoder.encodeVector[Chapter].apply(album.chapters),
        "spreads"          -> Json.arr((opening +: chapterSpreads)*),
        "countries"        -> Encoder.encodeList[Country].apply(found.countries.toList),
        "photoInventories" -> Json.obj(found.photoInventories.toVector.map { case (chapter, snapshot) =>
          chapter.value.toString -> ChapterPhotoJson.encodePhotos(snapshot)
        }*)
      )

    private def encodeEvent(event: Event) = event match
      case Event.Subscribed                => ServerSentEvent(data = "{}".some, eventType = "subscribed".some)
      case Event.InventoryChanged(chapter) =>
        val data = Json.obj("chapterId" -> Json.fromString(chapter.value.toString)).noSpaces
        ServerSentEvent(data = data.some, eventType = "inventory-changed".some)

    private object CanonicalId:
      def unapply(value: String): Option[UUID] =
        Either.catchOnly[IllegalArgumentException](UUID.fromString(value)).toOption.filter(_.toString === value)
