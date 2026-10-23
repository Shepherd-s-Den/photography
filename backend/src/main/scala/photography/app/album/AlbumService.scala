package photography.app.album

import cats.Monad
import cats.effect.{Clock, Sync}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

final case class AlbumPage(albums: Vector[Album], page: Int, pageSize: Int, totalAlbums: Long, totalPages: Int)

final case class AlbumPageSlice(totalAlbums: Long, albums: Vector[Album])

enum AlbumRepositoryFailure derives CanEqual:
  case InvalidAlbumData
  case InvalidTemplate
  case DatabaseFailure

enum AlbumServiceFailure derives CanEqual:
  case InvalidTitle
  case AlbumNotFound
  case ServiceFailure

trait UUIDGenerator[F[_]]:
  def nextUUID: F[UUID]

object UUIDGenerator:
  def make[F[_]: Sync]: UUIDGenerator[F] = new LiveUUIDGenerator[F]

  final private class LiveUUIDGenerator[F[_]: Sync] extends UUIDGenerator[F]:
    override def nextUUID: F[UUID] = Sync[F].delay(UUID.randomUUID())

trait UtcClock[F[_]]:
  def now: F[Instant]

object UtcClock:
  def make[F[_]: Clock]: UtcClock[F] = new LiveUtcClock[F]

  final private class LiveUtcClock[F[_]: Clock] extends UtcClock[F]:
    override def now: F[Instant] = Clock[F].realTimeInstant

trait AlbumRepository[F[_]]:
  def list(offset: Int, limit: Int): F[Either[AlbumRepositoryFailure, AlbumPageSlice]]
  def builtInAlbumStartTemplate: F[Either[AlbumRepositoryFailure, Option[SpreadTemplate]]]
  def create(album: Album, start: Spread.AlbumStart, cover: Cover): F[Either[AlbumRepositoryFailure, Unit]]
  def getAlbum(id: AlbumId): F[Either[AlbumRepositoryFailure, Option[Album]]]
  def rename(id: AlbumId, title: AlbumName): F[Either[AlbumRepositoryFailure, Option[Album]]]
  def delete(id: AlbumId): F[Either[AlbumRepositoryFailure, Boolean]]

trait AlbumService[F[_]]:
  def list(page: Int): F[Either[AlbumServiceFailure, AlbumPage]]
  def create(title: String): F[Either[AlbumServiceFailure, Album]]
  def get(id: AlbumId): F[Either[AlbumServiceFailure, Album]]
  def rename(id: AlbumId, title: String): F[Either[AlbumServiceFailure, Album]]
  def delete(id: AlbumId): F[Either[AlbumServiceFailure, Unit]]

object AlbumService:
  private val pageSize = 6

  def make[F[_]: Monad](repository: AlbumRepository[F], identifiers: UUIDGenerator[F], clock: UtcClock[F]): AlbumService[F] =
    new LiveAlbumService(repository, identifiers, clock)

  final private class LiveAlbumService[F[_]: Monad](
      repository: AlbumRepository[F],
      identifiers: UUIDGenerator[F],
      clock: UtcClock[F]
  ) extends AlbumService[F]:

    override def list(page: Int): F[Either[AlbumServiceFailure, AlbumPage]] =
      repository.list(offset = 0, limit = pageSize).flatMap:
        case Left(_)           => AlbumServiceFailure.ServiceFailure.asLeft[AlbumPage].pure[F]
        case Right(firstSlice) =>
          val totalPages   = ((firstSlice.totalAlbums + pageSize - 1) / pageSize).toInt
          val selectedPage = if totalPages === 0 then 1 else page.max(1).min(totalPages)
          val offset       = (selectedPage - 1) * pageSize
          if offset === 0 then
            AlbumPage(
              albums = firstSlice.albums,
              page = selectedPage,
              pageSize = pageSize,
              totalAlbums = firstSlice.totalAlbums,
              totalPages = totalPages
            ).asRight[AlbumServiceFailure].pure[F]
          else
            repository.list(offset = offset, limit = pageSize).map:
              case Left(_)      => AlbumServiceFailure.ServiceFailure.asLeft[AlbumPage]
              case Right(slice) =>
                AlbumPage(
                  albums = slice.albums,
                  page = selectedPage,
                  pageSize = pageSize,
                  totalAlbums = firstSlice.totalAlbums,
                  totalPages = totalPages
                ).asRight[AlbumServiceFailure]

    override def create(title: String): F[Either[AlbumServiceFailure, Album]] =
      val trimmedTitle = title.trim
      if trimmedTitle.isEmpty then AlbumServiceFailure.InvalidTitle.asLeft[Album].pure[F]
      else
        repository.builtInAlbumStartTemplate.flatMap:
          case Left(_)                                                       => AlbumServiceFailure.ServiceFailure.asLeft[Album].pure[F]
          case Right(None)                                                   => AlbumServiceFailure.ServiceFailure.asLeft[Album].pure[F]
          case Right(Some(template)) if !isValidAlbumStartTemplate(template) =>
            AlbumServiceFailure.ServiceFailure.asLeft[Album].pure[F]
          case Right(Some(template)) =>
            buildInitialState(trimmedTitle, template).flatMap: initialState =>
              val (album, start, cover) = initialState
              repository.create(album, start, cover).map:
                case Left(_)  => AlbumServiceFailure.ServiceFailure.asLeft[Album]
                case Right(_) => album.asRight[AlbumServiceFailure]

    override def get(id: AlbumId): F[Either[AlbumServiceFailure, Album]] =
      repository.getAlbum(id).map:
        case Left(_)            => AlbumServiceFailure.ServiceFailure.asLeft[Album]
        case Right(None)        => AlbumServiceFailure.AlbumNotFound.asLeft[Album]
        case Right(Some(album)) => album.asRight[AlbumServiceFailure]

    override def rename(id: AlbumId, title: String): F[Either[AlbumServiceFailure, Album]] =
      val trimmedTitle = title.trim
      if trimmedTitle.isEmpty then AlbumServiceFailure.InvalidTitle.asLeft[Album].pure[F]
      else
        repository.rename(id, trimmedTitle.refineUnsafe[io.github.iltotore.iron.constraint.all.Not[Empty]]).map:
          case Left(_)            => AlbumServiceFailure.ServiceFailure.asLeft[Album]
          case Right(None)        => AlbumServiceFailure.AlbumNotFound.asLeft[Album]
          case Right(Some(album)) => album.asRight[AlbumServiceFailure]

    override def delete(id: AlbumId): F[Either[AlbumServiceFailure, Unit]] =
      repository.delete(id).map:
        case Left(_)      => AlbumServiceFailure.ServiceFailure.asLeft[Unit]
        case Right(false) => AlbumServiceFailure.AlbumNotFound.asLeft[Unit]
        case Right(true)  => ().asRight[AlbumServiceFailure]

    private def buildInitialState(title: String, template: SpreadTemplate) =
      val textSlotBoxes = template.slots.toSortedSet.toVector.collect:
        case SpreadTemplateSlot.Text(box) => box
      for
        albumId   <- identifiers.nextUUID.map(AlbumId(_))
        spreadId  <- identifiers.nextUUID.map(SpreadId(_))
        slotIds   <- textSlotBoxes.traverse(_ => identifiers.nextUUID)
        frontId   <- identifiers.nextUUID.map(CoverSlotId(_))
        backId    <- identifiers.nextUUID.map(CoverSlotId(_))
        createdAt <- clock.now.map(_.truncatedTo(ChronoUnit.MILLIS))
      yield
        val actualSlots = textSlotBoxes.zip(slotIds).map:
          case (box, id) => SpreadSlot.Text(TextSpreadSlotId(id), spreadId, box)
        val album = Album(
          id = albumId,
          title = title.refineUnsafe[io.github.iltotore.iron.constraint.all.Not[Empty]],
          synopsis = "",
          createdAt = createdAt
        )
        val start = Spread.AlbumStart(
          id = spreadId,
          album = albumId,
          template = template.id,
          slots = actualSlots,
          placements = Vector.empty[SpreadPlacement]
        )
        val cover = Cover(album = albumId, spineOwner = SpineOwner.Front, front = CoverSlot(frontId), back = CoverSlot(backId))
        (album, start, cover)

    private def isValidAlbumStartTemplate(template: SpreadTemplate) =
      template.kind match
        case SpreadTemplateKind.AlbumStart =>
          template.slots.toSortedSet.toVector match
            case Vector(SpreadTemplateSlot.Text(box)) =>
              val x: Double      = box.x
              val y: Double      = box.y
              val width: Double  = box.width
              val height: Double = box.height
              x === 0.0 && y === 0.0 && width === 1.0 && height === 1.0
            case _ => false
        case _ => false
