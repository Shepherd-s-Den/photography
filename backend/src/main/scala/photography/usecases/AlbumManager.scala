package photography.usecases

import cats.Monad
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.capabilities.{Clock, IdGenerator}
import photography.domain.album.{Album, AlbumId, AlbumTitle, AlbumYear, Chapter}
import photography.domain.layout.*
import photography.ports.AlbumRepository
import photography.usecases.AlbumManager.*
import photography.usecases.SpreadTemplateManager.ListAlbumStartTemplatesResult

import java.time.temporal.ChronoUnit

trait AlbumManager[F[_]]:
  def list(page: Int): F[ListAlbumsResult]
  def create(year: AlbumYear, title: AlbumTitle): F[CreateAlbumResult]
  def get(id: AlbumId): F[GetAlbumResult]
  def rename(id: AlbumId, title: AlbumTitle): F[RenameResult]
  def delete(id: AlbumId): F[DeleteAlbumResult]

type AlbumPageSize = Int :| Positive

object AlbumManager:
  def make[F[_]: Monad: IdGenerator: Clock](
      repository: AlbumRepository[F],
      spreads: SpreadTemplateManager[F],
      pageSize: AlbumPageSize
  ): AlbumManager[F] =
    new LiveAlbumManager(repository, spreads, pageSize)

  final private class LiveAlbumManager[F[_]: Monad: IdGenerator: Clock](
      repository: AlbumRepository[F],
      spreads: SpreadTemplateManager[F],
      pageSize: AlbumPageSize
  ) extends AlbumManager[F]:

    override def list(page: Int): F[ListAlbumsResult] =
      repository.list(offset = 0, limit = pageSize).flatMap:
        case AlbumRepository.ListResult.Failed(_)                 => ListAlbumsResult.Failed.pure[F]
        case firstPage @ AlbumRepository.ListResult.Listed(slice) => buildListResult(page, slice.totalAlbums, firstPage)

    override def create(year: AlbumYear, title: AlbumTitle): F[CreateAlbumResult] =
      spreads.listAlbumStartTemplates.flatMap:
        case ListAlbumStartTemplatesResult.Listed(templates) =>
          buildInitialComposition(year, title, templates.head).flatMap { case (album, cover) =>
            repository.create(album, cover).map:
              case AlbumRepository.CreateResult.Created                                         => CreateAlbumResult.Created(album)
              case AlbumRepository.CreateResult.Failed(AlbumRepository.Failure.YearAlreadyUsed) => CreateAlbumResult.YearAlreadyUsed
              case AlbumRepository.CreateResult.Failed(_)                                       => CreateAlbumResult.Failed
          }
        case ListAlbumStartTemplatesResult.Failed => CreateAlbumResult.Failed.pure[F]

    override def get(id: AlbumId): F[GetAlbumResult] =
      repository.get(id).map:
        case AlbumRepository.GetResult.Found(album) => GetAlbumResult.Found(album)
        case AlbumRepository.GetResult.NotFound     => GetAlbumResult.NotFound
        case AlbumRepository.GetResult.Failed(_)    => GetAlbumResult.Failed

    override def rename(id: AlbumId, title: AlbumTitle): F[RenameResult] =
      repository.rename(id, title = title).map:
        case AlbumRepository.RenameResult.Renamed(album) => RenameResult.Renamed(album)
        case AlbumRepository.RenameResult.NotFound       => RenameResult.NotFound
        case AlbumRepository.RenameResult.Failed(_)      => RenameResult.Failed

    override def delete(id: AlbumId): F[DeleteAlbumResult] =
      repository.delete(id).map:
        case AlbumRepository.DeleteResult.Deleted   => DeleteAlbumResult.Deleted
        case AlbumRepository.DeleteResult.NotFound  => DeleteAlbumResult.NotFound
        case AlbumRepository.DeleteResult.Failed(_) => DeleteAlbumResult.Failed

    private def buildListResult(requestedPage: Int, totalAlbums: Long, firstPage: AlbumRepository.ListResult) =
      val totalPages    = ((totalAlbums + pageSize - 1) / pageSize).toInt
      val selectedPage  = if totalPages === 0 then 1 else requestedPage.max(1).min(totalPages)
      val offset        = (selectedPage - 1) * pageSize
      val selectedSlice = if offset === 0 then firstPage.pure[F] else repository.list(offset = offset, limit = pageSize)
      selectedSlice.map:
        case AlbumRepository.ListResult.Listed(slice) =>
          ListAlbumsResult.Listed(AlbumPage(
            slice.albums,
            page = selectedPage,
            pageSize = pageSize,
            totalAlbums = totalAlbums,
            totalPages = totalPages
          ))
        case AlbumRepository.ListResult.Failed(_) => ListAlbumsResult.Failed

    private def buildInitialComposition(year: AlbumYear, title: AlbumTitle, template: SpreadTemplate.AlbumStart) =
      val newPhotos =
        template.photoSlots.traverse(box => IdGenerator[F].generateId.map(id => SpreadSlot.Photo(PhotoSpreadSlotId(id), box, bleed = Set.empty)))
      for
        albumId   <- IdGenerator[F].generateId.map(AlbumId(_))
        spreadId  <- IdGenerator[F].generateId.map(SpreadId(_))
        photos    <- newPhotos
        text      <- IdGenerator[F].generateId.map(id => SpreadSlot.Text(TextSpreadSlotId(id), template.textSlot))
        frontId   <- IdGenerator[F].generateId.map(CoverSlotId(_))
        backId    <- IdGenerator[F].generateId.map(CoverSlotId(_))
        createdAt <- Clock[F].readInstant.map(_.truncatedTo(ChronoUnit.MILLIS))
      yield
        val start = Spread.AlbumStart(spreadId, template.id, photoSlots = photos, textSlot = text, placements = Vector.empty[SpreadPlacement])
        val album = Album(albumId, year = year, title = title, synopsis = "", createdAt = createdAt, start, chapters = Vector.empty[Chapter])
        val cover = Cover(albumId, SpineOwner.Front, front = CoverSlot(frontId), back = CoverSlot(backId))
        (album, cover)

  final case class AlbumPage(albums: Vector[Album], page: Int, pageSize: Int, totalAlbums: Long, totalPages: Int)

  enum ListAlbumsResult derives CanEqual:
    case Listed(page: AlbumPage)
    case Failed

  enum CreateAlbumResult derives CanEqual:
    case Created(album: Album)
    case YearAlreadyUsed, Failed

  enum GetAlbumResult derives CanEqual:
    case Found(album: Album)
    case NotFound
    case Failed

  enum RenameResult derives CanEqual:
    case Renamed(album: Album)
    case NotFound, Failed

  enum DeleteAlbumResult derives CanEqual:
    case Deleted
    case NotFound
    case Failed
