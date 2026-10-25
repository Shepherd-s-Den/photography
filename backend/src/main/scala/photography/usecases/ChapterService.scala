package photography.usecases

import cats.Monad
import cats.syntax.all.*
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository}
import photography.usecases.ChapterService.*
import photography.usecases.SpreadTemplateService.ListChapterStartTemplatesResult

trait ChapterService[F[_]]:
  def create(album: AlbumId, name: DestinationName, country: Country, period: Period, sourceFolder: SourceFolder): F[CreateResult]
  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      country: Country,
      period: Period,
      sourceFolder: SourceFolder
  ): F[UpdateResult]

object ChapterService:
  def make[F[_]: Monad: IdGenerator](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateService[F],
      policy: ChapterPolicy
  ): ChapterService[F] =
    new LiveChapterService(albums, chapters, templates, policy)

  final private class LiveChapterService[F[_]: Monad: IdGenerator](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateService[F],
      policy: ChapterPolicy
  ) extends ChapterService[F]:
    override def create(album: AlbumId, name: DestinationName, country: Country, period: Period, sourceFolder: SourceFolder): F[CreateResult] =
      albums.get(album).flatMap:
        case AlbumRepository.GetResult.Found(current) => createChapter(current, name, country, period, sourceFolder)
        case AlbumRepository.GetResult.NotFound       => CreateResult.AlbumNotFound.pure[F]
        case AlbumRepository.GetResult.Failed(_)      => CreateResult.Failed.pure[F]

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        country: Country,
        period: Period,
        sourceFolder: SourceFolder
    ): F[UpdateResult] =
      albums.get(album).flatMap:
        case AlbumRepository.GetResult.Found(current) =>
          chapters.update(album, chapter, name, country, resolvePeriod(current.year, period), sourceFolder).map(toUpdateResult)
        case AlbumRepository.GetResult.NotFound  => UpdateResult.AlbumNotFound.pure[F]
        case AlbumRepository.GetResult.Failed(_) => UpdateResult.Failed.pure[F]

    private def createChapter(album: Album, name: DestinationName, country: Country, period: Period, sourceFolder: SourceFolder) =
      templates.listChapterStartTemplates.flatMap:
        case ListChapterStartTemplatesResult.Listed(available) =>
          buildChapter(name, country, resolvePeriod(album.year, period), sourceFolder, available.head).flatMap: chapter =>
            chapters.create(album.id, chapter).map(toCreateResult(_, chapter.id))
        case ListChapterStartTemplatesResult.Failed => CreateResult.Failed.pure[F]

    private def resolvePeriod(year: AlbumYear, period: Period) = period match
      case Period.AlbumYear    => policy.yearPeriod(year)
      case Period.Dates(value) => value

    private def buildChapter(
        name: DestinationName,
        country: Country,
        period: VisitPeriod,
        sourceFolder: SourceFolder,
        template: SpreadTemplate.ChapterStart
    ) =
      for
        id          <- IdGenerator[F].nextId.map(ChapterId(_))
        opening     <- buildOpening(template)
        destination <- IdGenerator[F].nextId.map(value => Destination(DestinationId(value), name, country))
      yield Chapter(id, destination, period, description = "", sourceFolder, opening, spreads = Vector.empty[Spread.PhotosOnly])

    private def buildOpening(template: SpreadTemplate.ChapterStart) =
      val newPhotos =
        template.photoSlots.traverse(box => IdGenerator[F].nextId.map(slot => SpreadSlot.Photo(PhotoSpreadSlotId(slot), box, bleed = Set.empty)))
      for
        id     <- IdGenerator[F].nextId.map(SpreadId(_))
        photos <- newPhotos
        text   <- IdGenerator[F].nextId.map(slot => SpreadSlot.Text(TextSpreadSlotId(slot), template.textSlot))
      yield Spread.ChapterStart(id, template.id, photos, text, placements = Vector.empty[SpreadPlacement])

    private def toCreateResult(result: ChapterRepository.CreateResult, id: ChapterId) = result match
      case ChapterRepository.CreateResult.Saved            => CreateResult.Created(id)
      case ChapterRepository.CreateResult.Rejected(reason) => CreateResult.Rejected(reason)
      case ChapterRepository.CreateResult.AlbumNotFound    => CreateResult.AlbumNotFound
      case ChapterRepository.CreateResult.Failed           => CreateResult.Failed

    private def toUpdateResult(result: ChapterRepository.UpdateResult) = result match
      case ChapterRepository.UpdateResult.Saved            => UpdateResult.Updated
      case ChapterRepository.UpdateResult.Rejected(reason) => UpdateResult.Rejected(reason)
      case ChapterRepository.UpdateResult.ChapterNotFound  => UpdateResult.ChapterNotFound
      case ChapterRepository.UpdateResult.AlbumNotFound    => UpdateResult.AlbumNotFound
      case ChapterRepository.UpdateResult.Failed           => UpdateResult.Failed

  enum CreateResult:
    case Created(id: ChapterId)
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed

  enum UpdateResult:
    case Updated
    case Rejected(reason: ChapterConflict)
    case ChapterNotFound, AlbumNotFound, Failed

  enum Period:
    case AlbumYear
    case Dates(value: VisitPeriod)
