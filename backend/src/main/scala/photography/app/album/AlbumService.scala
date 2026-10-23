package photography.app.album

import cats.Monad
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.app.capabilities.{Clock, IdGenerator}
import photography.app.spread.SpreadRepository
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*

import java.time.temporal.ChronoUnit

type AlbumPageSize = Int :| Positive

final case class AlbumPage(albums: Vector[Album], page: Int, pageSize: Int, totalAlbums: Long, totalPages: Int)

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

trait AlbumService[F[_]]:
  def list(page: Int): F[ListAlbumsResult]
  def create(title: String): F[CreateAlbumResult]
  def get(id: AlbumId): F[GetAlbumResult]
  def rename(id: AlbumId, title: String): F[RenameAlbumResult]
  def delete(id: AlbumId): F[DeleteAlbumResult]

object AlbumService:
  def make[F[_]: Monad](
      repository: AlbumRepository[F],
      spreads: SpreadRepository[F],
      identifiers: IdGenerator[F],
      clock: Clock[F],
      pageSize: AlbumPageSize
  ): AlbumService[F] =
    new LiveAlbumService(repository, spreads, identifiers, clock, pageSize)

  final private class LiveAlbumService[F[_]: Monad](
      repository: AlbumRepository[F],
      spreads: SpreadRepository[F],
      identifiers: IdGenerator[F],
      clock: Clock[F],
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
      spreads.listAlbumStartTemplates.flatMap:
        case SpreadRepository.ListAlbumStartTemplatesResult.Listed(template +: _) if isAlbumStartComposition(template) =>
          buildInitialComposition(title, template).flatMap: (album, start, cover) =>
            repository.create(album, start, cover).map:
              case AlbumRepository.CreateResult.Created   => CreateAlbumResult.Created(album)
              case AlbumRepository.CreateResult.Failed(_) => CreateAlbumResult.Failed
        case SpreadRepository.ListAlbumStartTemplatesResult.Listed(_) => CreateAlbumResult.Failed.pure[F]
        case SpreadRepository.ListAlbumStartTemplatesResult.Failed(_) => CreateAlbumResult.Failed.pure[F]

    private def isAlbumStartComposition(template: SpreadTemplate): Boolean =
      val isFullSpreadText = template.slots.toSortedSet.toVector match
        case Vector(SpreadTemplateSlot.Text(SlotBox(x, y, width, height))) =>
          (x: Double) === 0.0 && (y: Double) === 0.0 && (width: Double) === 1.0 && (height: Double) === 1.0
        case _ => false
      template.kind match
        case SpreadTemplateKind.AlbumStart => isFullSpreadText
        case _                             => false

    private def buildInitialComposition(title: AlbumName, template: SpreadTemplate): F[(Album, Spread.AlbumStart, Cover)] =
      val textSlotBoxes = template.slots.toSortedSet.toVector.collect:
        case SpreadTemplateSlot.Text(box) => box
      for
        albumId   <- identifiers.nextId.map(AlbumId(_))
        spreadId  <- identifiers.nextId.map(SpreadId(_))
        slotIds   <- textSlotBoxes.traverse(_ => identifiers.nextId)
        frontId   <- identifiers.nextId.map(CoverSlotId(_))
        backId    <- identifiers.nextId.map(CoverSlotId(_))
        createdAt <- clock.now.map(_.truncatedTo(ChronoUnit.MILLIS))
      yield
        val slots = textSlotBoxes.zip(slotIds).map: (box, id) =>
          SpreadSlot.Text(TextSpreadSlotId(id), spreadId, box)
        val album = Album(albumId, title, synopsis = "", createdAt)
        val start = Spread.AlbumStart(spreadId, albumId, template.id, slots, Vector.empty[SpreadPlacement])
        val cover = Cover(albumId, SpineOwner.Front, CoverSlot(frontId), CoverSlot(backId))
        (album, start, cover)
