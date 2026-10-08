package photography.app.album

import cats.Monad
import cats.effect.{Clock, Sync}
import cats.syntax.all.*
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import photography.domain.album.{Album, AlbumComposition, AlbumId, AlbumName}
import photography.domain.layout.*

import java.time.Instant
import java.util.UUID

final case class AlbumSummary(id: AlbumId, title: AlbumName, createdAt: Instant)

final case class AlbumPage(
    albums: Vector[AlbumSummary],
    page: Int,
    pageSize: Int,
    totalAlbums: Long,
    totalPages: Int
)

final case class AlbumPageSlice(totalAlbums: Long, albums: Vector[AlbumSummary])

enum AlbumRepositoryFailure derives CanEqual:
  case IncompleteComposition
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
  def create(composition: AlbumComposition): F[Either[AlbumRepositoryFailure, Unit]]
  def getComposition(id: AlbumId): F[Either[AlbumRepositoryFailure, Option[AlbumComposition]]]
  def rename(id: AlbumId, title: AlbumName): F[Either[AlbumRepositoryFailure, Option[AlbumSummary]]]
  def delete(id: AlbumId): F[Either[AlbumRepositoryFailure, Boolean]]

trait AlbumService[F[_]]:
  def list(page: Int): F[Either[AlbumServiceFailure, AlbumPage]]
  def create(title: String): F[Either[AlbumServiceFailure, AlbumSummary]]
  def get(id: AlbumId): F[Either[AlbumServiceFailure, AlbumComposition]]
  def rename(id: AlbumId, title: String): F[Either[AlbumServiceFailure, AlbumSummary]]
  def delete(id: AlbumId): F[Either[AlbumServiceFailure, Unit]]

object AlbumService:
  private val pageSize = 6

  def make[F[_]: Monad](
      repository: AlbumRepository[F],
      identifiers: UUIDGenerator[F],
      clock: UtcClock[F]
  ): AlbumService[F] =
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

    override def create(title: String): F[Either[AlbumServiceFailure, AlbumSummary]] =
      val trimmedTitle = title.trim
      if trimmedTitle.isEmpty then AlbumServiceFailure.InvalidTitle.asLeft[AlbumSummary].pure[F]
      else
        repository.builtInAlbumStartTemplate.flatMap:
          case Left(_)                                                     => AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary].pure[F]
          case Right(None)                                                 => AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary].pure[F]
          case Right(Some(template)) if !validAlbumStartTemplate(template) =>
            AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary].pure[F]
          case Right(Some(template)) =>
            createComposition(trimmedTitle, template).flatMap: composition =>
              repository.create(composition).map:
                case Left(_)  => AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary]
                case Right(_) =>
                  AlbumSummary(
                    id = composition.album.id,
                    title = composition.album.title,
                    createdAt = composition.album.createdAt
                  ).asRight[AlbumServiceFailure]

    override def get(id: AlbumId): F[Either[AlbumServiceFailure, AlbumComposition]] =
      repository.getComposition(id).map:
        case Left(_)                  => AlbumServiceFailure.ServiceFailure.asLeft[AlbumComposition]
        case Right(None)              => AlbumServiceFailure.AlbumNotFound.asLeft[AlbumComposition]
        case Right(Some(composition)) => composition.asRight[AlbumServiceFailure]

    override def rename(id: AlbumId, title: String): F[Either[AlbumServiceFailure, AlbumSummary]] =
      val trimmedTitle = title.trim
      if trimmedTitle.isEmpty then AlbumServiceFailure.InvalidTitle.asLeft[AlbumSummary].pure[F]
      else
        repository.rename(id, trimmedTitle.refineUnsafe[io.github.iltotore.iron.constraint.all.Not[Empty]]).map:
          case Left(_)              => AlbumServiceFailure.ServiceFailure.asLeft[AlbumSummary]
          case Right(None)          => AlbumServiceFailure.AlbumNotFound.asLeft[AlbumSummary]
          case Right(Some(summary)) => summary.asRight[AlbumServiceFailure]

    override def delete(id: AlbumId): F[Either[AlbumServiceFailure, Unit]] =
      repository.delete(id).map:
        case Left(_)      => AlbumServiceFailure.ServiceFailure.asLeft[Unit]
        case Right(false) => AlbumServiceFailure.AlbumNotFound.asLeft[Unit]
        case Right(true)  => ().asRight[AlbumServiceFailure]

    private def createComposition(title: String, template: SpreadTemplate): F[AlbumComposition] =
      val textSlotBoxes = template.slots.toSortedSet.toVector.collect:
        case SpreadTemplateSlot.Text(box) => box
      for
        albumId   <- identifiers.nextUUID.map(AlbumId(_))
        spreadId  <- identifiers.nextUUID.map(SpreadId(_))
        slotIds   <- textSlotBoxes.traverse(_ => identifiers.nextUUID)
        frontId   <- identifiers.nextUUID.map(CoverSlotId(_))
        backId    <- identifiers.nextUUID.map(CoverSlotId(_))
        createdAt <- clock.now
      yield
        val actualSlots = textSlotBoxes.zip(slotIds).map:
          case (box, id) => SpreadSlot.Text(TextSpreadSlotId(id), spreadId, box)
        val album = Album(
          id = albumId,
          title = title.refineUnsafe[io.github.iltotore.iron.constraint.all.Not[Empty]],
          synopsis = "",
          createdAt = createdAt
        )
        AlbumComposition(
          album = album,
          start = SpreadComposition(
            id = spreadId,
            templateId = template.id,
            slots = actualSlots,
            placements = Vector.empty[SpreadPlacement]
          ),
          cover = CoverComposition(
            spineOwner = SpineOwner.Front,
            front = CoverSlot(frontId),
            frontPlacement = None,
            back = CoverSlot(backId),
            backPlacement = None
          ),
          chapters = Vector.empty[photography.domain.album.ChapterComposition]
        )

    private def validAlbumStartTemplate(template: SpreadTemplate): Boolean =
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
