package photography.usecases

import cats.Monad
import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.capabilities.{Clock, IdGenerator}
import photography.domain.album.{Album, AlbumId, AlbumName}
import photography.domain.layout.*
import photography.ports.AlbumRepository
import photography.usecases.AlbumService.*
import photography.usecases.SpreadTemplateService.ListAlbumStartTemplatesResult

import java.time.temporal.ChronoUnit

trait AlbumService[F[_]]:
  def list(page: Int): F[ListAlbumsResult]
  def create(title: AlbumName): F[CreateAlbumResult]
  def get(id: AlbumId): F[GetAlbumResult]
  def rename(id: AlbumId, title: AlbumName): F[RenameAlbumResult]
  def delete(id: AlbumId): F[DeleteAlbumResult]

type AlbumPageSize = Int :| Positive

object AlbumService:
  def make[F[_]: Monad](
      repository: AlbumRepository[F],
      spreads: SpreadTemplateService[F],
      identifiers: IdGenerator[F],
      clock: Clock[F],
      pageSize: AlbumPageSize
  ): AlbumService[F] =
    new LiveAlbumService(repository, spreads, identifiers, clock, pageSize)

  final private class LiveAlbumService[F[_]: Monad](
      repository: AlbumRepository[F],
      spreads: SpreadTemplateService[F],
      identifiers: IdGenerator[F],
      clock: Clock[F],
      pageSize: AlbumPageSize
  ) extends AlbumService[F]:

    override def list(page: Int): F[ListAlbumsResult] =
      repository.list(offset = 0, limit = pageSize).flatMap:
        case AlbumRepository.ListResult.Failed(_)                 => ListAlbumsResult.Failed.pure[F]
        case firstPage @ AlbumRepository.ListResult.Listed(slice) => buildListResult(page, slice.totalAlbums, firstPage)

    override def create(title: AlbumName): F[CreateAlbumResult] =
      spreads.listAlbumStartTemplates.flatMap:
        case ListAlbumStartTemplatesResult.Listed(templates) =>
          buildInitialComposition(title, templates.head).flatMap: (album, cover) =>
            repository.create(album, cover).map:
              case AlbumRepository.CreateResult.Created   => CreateAlbumResult.Created(album)
              case AlbumRepository.CreateResult.Failed(_) => CreateAlbumResult.Failed
        case ListAlbumStartTemplatesResult.Failed => CreateAlbumResult.Failed.pure[F]

    override def get(id: AlbumId): F[GetAlbumResult] =
      repository.get(id).map:
        case AlbumRepository.GetResult.Found(album) => GetAlbumResult.Found(album)
        case AlbumRepository.GetResult.NotFound     => GetAlbumResult.NotFound
        case AlbumRepository.GetResult.Failed(_)    => GetAlbumResult.Failed

    override def rename(id: AlbumId, title: AlbumName): F[RenameAlbumResult] =
      repository.rename(id, title).map:
        case AlbumRepository.RenameResult.Renamed(album) => RenameAlbumResult.Renamed(album)
        case AlbumRepository.RenameResult.NotFound       => RenameAlbumResult.NotFound
        case AlbumRepository.RenameResult.Failed(_)      => RenameAlbumResult.Failed

    override def delete(id: AlbumId): F[DeleteAlbumResult] =
      repository.delete(id).map:
        case AlbumRepository.DeleteResult.Deleted   => DeleteAlbumResult.Deleted
        case AlbumRepository.DeleteResult.NotFound  => DeleteAlbumResult.NotFound
        case AlbumRepository.DeleteResult.Failed(_) => DeleteAlbumResult.Failed

    private def buildListResult(requestedPage: Int, totalAlbums: Long, firstPage: AlbumRepository.ListResult) =
      val totalPages    = ((totalAlbums + pageSize - 1) / pageSize).toInt
      val selectedPage  = if totalPages === 0 then 1 else requestedPage.max(1).min(totalPages)
      val offset        = (selectedPage - 1) * pageSize
      val selectedSlice = if offset === 0 then firstPage.pure[F] else repository.list(offset, pageSize)
      selectedSlice.map:
        case AlbumRepository.ListResult.Listed(slice) =>
          ListAlbumsResult.Listed(AlbumPage(slice.albums, selectedPage, pageSize, totalAlbums, totalPages))
        case AlbumRepository.ListResult.Failed(_) => ListAlbumsResult.Failed

    // The album spread invariant (first spread is an AlbumStart, then chapter blocks of a ChapterStart
    // followed by its PhotosOnly spreads) holds by construction: a new album starts with a single AlbumStart.
    // Any future spread edit must validate the invariant here before writing.
    private def buildInitialComposition(title: AlbumName, template: SpreadTemplate) =
      val textSlotBoxes =
        template.slots.toList
          .collect:
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
        val start = Spread.AlbumStart(spreadId, albumId, template.id, slots.toVector, Vector.empty[SpreadPlacement])
        val album = Album(albumId, title, synopsis = "", createdAt, NonEmptyList.one(start: Spread))
        val cover = Cover(albumId, SpineOwner.Front, CoverSlot(frontId), CoverSlot(backId))
        (album, cover)

  final case class AlbumPage(albums: Vector[Album], page: Int, pageSize: Int, totalAlbums: Long, totalPages: Int)

  enum ListAlbumsResult derives CanEqual:
    case Listed(page: AlbumPage)
    case Failed

  enum CreateAlbumResult derives CanEqual:
    case Created(album: Album)
    case Failed

  enum GetAlbumResult derives CanEqual:
    case Found(album: Album)
    case NotFound
    case Failed

  enum RenameAlbumResult derives CanEqual:
    case Renamed(album: Album)
    case NotFound
    case Failed

  enum DeleteAlbumResult derives CanEqual:
    case Deleted
    case NotFound
    case Failed
