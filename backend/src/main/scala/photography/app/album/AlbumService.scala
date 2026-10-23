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

type AlbumPageSize = Int :| Positive

final case class AlbumPage(albums: Vector[Album], page: Int, pageSize: Int, totalAlbums: Long, totalPages: Int)

final case class AlbumPageSlice(totalAlbums: Long, albums: Vector[Album])

enum AlbumRepositoryFailure derives CanEqual:
  case InvalidAlbumData, InvalidTemplate, DatabaseFailure

enum ListAlbumsResult derives CanEqual:
  case Listed(page: AlbumPage)
  case Failed

enum CreateAlbumResult derives CanEqual:
  case Created(album: Album)
  case InvalidTitle
  case Failed

enum GetAlbumResult derives CanEqual:
  case Found(album: Album)
  case NotFound
  case Failed

enum RenameAlbumResult derives CanEqual:
  case Renamed(album: Album)
  case InvalidTitle
  case NotFound
  case Failed

enum DeleteAlbumResult derives CanEqual:
  case Deleted
  case NotFound
  case Failed

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
  def list(offset: Int, limit: Int): F[AlbumRepository.ListResult]
  def findAlbumStartTemplate: F[AlbumRepository.FindAlbumStartTemplateResult]
  def create(album: Album, start: Spread.AlbumStart, cover: Cover): F[AlbumRepository.CreateResult]
  def getAlbum(id: AlbumId): F[AlbumRepository.GetResult]
  def rename(id: AlbumId, title: AlbumName): F[AlbumRepository.RenameResult]
  def delete(id: AlbumId): F[AlbumRepository.DeleteResult]

object AlbumRepository:
  enum ListResult derives CanEqual:
    case Listed(slice: AlbumPageSlice)
    case Failed(reason: AlbumRepositoryFailure)

  enum FindAlbumStartTemplateResult derives CanEqual:
    case Found(template: SpreadTemplate)
    case Missing
    case Failed(reason: AlbumRepositoryFailure)

  enum CreateResult derives CanEqual:
    case Created
    case Failed(reason: AlbumRepositoryFailure)

  enum GetResult derives CanEqual:
    case Found(album: Album)
    case NotFound
    case Failed(reason: AlbumRepositoryFailure)

  enum RenameResult derives CanEqual:
    case Renamed(album: Album)
    case NotFound
    case Failed(reason: AlbumRepositoryFailure)

  enum DeleteResult derives CanEqual:
    case Deleted
    case NotFound
    case Failed(reason: AlbumRepositoryFailure)

trait AlbumService[F[_]]:
  def list(page: Int): F[ListAlbumsResult]
  def create(title: String): F[CreateAlbumResult]
  def get(id: AlbumId): F[GetAlbumResult]
  def rename(id: AlbumId, title: String): F[RenameAlbumResult]
  def delete(id: AlbumId): F[DeleteAlbumResult]

object AlbumService:
  def make[F[_]: Monad](repository: AlbumRepository[F], identifiers: UUIDGenerator[F], clock: UtcClock[F], pageSize: AlbumPageSize): AlbumService[F] =
    new LiveAlbumService(repository, identifiers, clock, pageSize)

  final private class LiveAlbumService[F[_]: Monad](
      repository: AlbumRepository[F],
      identifiers: UUIDGenerator[F],
      clock: UtcClock[F],
      pageSize: AlbumPageSize
  ) extends AlbumService[F]:

    override def list(page: Int): F[ListAlbumsResult] =
      repository.list(offset = 0, limit = pageSize).flatMap:
        case AlbumRepository.ListResult.Failed(_)                 => ListAlbumsResult.Failed.pure[F]
        case firstPage @ AlbumRepository.ListResult.Listed(slice) => buildListResult(page, slice.totalAlbums, firstPage)

    override def create(title: String): F[CreateAlbumResult] =
      title.trim.refineOption[Not[Empty]] match
        case None             => CreateAlbumResult.InvalidTitle.pure[F]
        case Some(validTitle) => createAlbum(validTitle)

    override def get(id: AlbumId): F[GetAlbumResult] =
      repository.getAlbum(id).map:
        case AlbumRepository.GetResult.Found(album) => GetAlbumResult.Found(album)
        case AlbumRepository.GetResult.NotFound     => GetAlbumResult.NotFound
        case AlbumRepository.GetResult.Failed(_)    => GetAlbumResult.Failed

    override def rename(id: AlbumId, title: String): F[RenameAlbumResult] =
      title.trim.refineOption[Not[Empty]] match
        case None             => RenameAlbumResult.InvalidTitle.pure[F]
        case Some(validTitle) =>
          repository.rename(id, validTitle).map:
            case AlbumRepository.RenameResult.Renamed(album) => RenameAlbumResult.Renamed(album)
            case AlbumRepository.RenameResult.NotFound       => RenameAlbumResult.NotFound
            case AlbumRepository.RenameResult.Failed(_)      => RenameAlbumResult.Failed

    override def delete(id: AlbumId): F[DeleteAlbumResult] =
      repository.delete(id).map:
        case AlbumRepository.DeleteResult.Deleted   => DeleteAlbumResult.Deleted
        case AlbumRepository.DeleteResult.NotFound  => DeleteAlbumResult.NotFound
        case AlbumRepository.DeleteResult.Failed(_) => DeleteAlbumResult.Failed

    private def buildListResult(requestedPage: Int, totalAlbums: Long, firstPage: AlbumRepository.ListResult): F[ListAlbumsResult] =
      val totalPages    = ((totalAlbums + pageSize - 1) / pageSize).toInt
      val selectedPage  = if totalPages === 0 then 1 else requestedPage.max(1).min(totalPages)
      val offset        = (selectedPage - 1) * pageSize
      val selectedSlice = if offset === 0 then firstPage.pure[F] else repository.list(offset, pageSize)
      selectedSlice.map:
        case AlbumRepository.ListResult.Listed(slice) =>
          ListAlbumsResult.Listed(AlbumPage(slice.albums, selectedPage, pageSize, totalAlbums, totalPages))
        case AlbumRepository.ListResult.Failed(_) => ListAlbumsResult.Failed

    private def createAlbum(title: AlbumName): F[CreateAlbumResult] =
      repository.findAlbumStartTemplate.flatMap:
        case AlbumRepository.FindAlbumStartTemplateResult.Found(template) if isAlbumStartTemplate(template) =>
          buildInitialComposition(title, template).flatMap: (album, start, cover) =>
            repository.create(album, start, cover).map:
              case AlbumRepository.CreateResult.Created   => CreateAlbumResult.Created(album)
              case AlbumRepository.CreateResult.Failed(_) => CreateAlbumResult.Failed
        case AlbumRepository.FindAlbumStartTemplateResult.Found(_)  => CreateAlbumResult.Failed.pure[F]
        case AlbumRepository.FindAlbumStartTemplateResult.Missing   => CreateAlbumResult.Failed.pure[F]
        case AlbumRepository.FindAlbumStartTemplateResult.Failed(_) => CreateAlbumResult.Failed.pure[F]

    private def buildInitialComposition(title: AlbumName, template: SpreadTemplate): F[(Album, Spread.AlbumStart, Cover)] =
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
        val slots = textSlotBoxes.zip(slotIds).map: (box, id) =>
          SpreadSlot.Text(TextSpreadSlotId(id), spreadId, box)
        val album = Album(albumId, title, synopsis = "", createdAt)
        val start = Spread.AlbumStart(spreadId, albumId, template.id, slots, Vector.empty[SpreadPlacement])
        val cover = Cover(albumId, SpineOwner.Front, CoverSlot(frontId), CoverSlot(backId))
        (album, start, cover)

    private def isAlbumStartTemplate(template: SpreadTemplate): Boolean =
      template.kind match
        case SpreadTemplateKind.AlbumStart => hasOnlyFullSpreadText(template.slots.toSortedSet.toVector)
        case _                             => false

    private def hasOnlyFullSpreadText(slots: Vector[SpreadTemplateSlot]): Boolean =
      slots match
        case Vector(SpreadTemplateSlot.Text(box)) => isFullSpreadBox(box)
        case _                                    => false

    private def isFullSpreadBox(box: SlotBox): Boolean =
      (box.x: Double) === 0.0 && (box.y: Double) === 0.0 && (box.width: Double) === 1.0 && (box.height: Double) === 1.0
