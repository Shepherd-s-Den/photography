package photography.usecases

import cats.Monad
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import photography.capabilities.IdGenerator
import photography.domain.album.*
import photography.domain.layout.*
import photography.ports.{AlbumRepository, ChapterRepository}
import photography.ports.ChapterRepository.SaveResult
import photography.usecases.ChapterService.*
import photography.usecases.SpreadTemplateService.ListChapterStartTemplatesResult

import java.time.{LocalDate, Year}

trait ChapterService[F[_]]:
  def create(album: AlbumId, name: DestinationName, country: Country, period: VisitPeriod, sourceFolder: SourceFolder): F[CreateResult]
  def update(
      album: AlbumId,
      chapter: ChapterId,
      name: DestinationName,
      country: Country,
      period: VisitPeriod,
      sourceFolder: SourceFolder
  ): F[UpdateResult]

object ChapterService:
  def make[F[_]: Monad: IdGenerator](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateService[F]
  ): ChapterService[F] =
    new LiveChapterService(albums, chapters, templates)

  final private class LiveChapterService[F[_]: Monad: IdGenerator](
      albums: AlbumRepository[F],
      chapters: ChapterRepository[F],
      templates: SpreadTemplateService[F]
  ) extends ChapterService[F]:
    override def create(album: AlbumId, name: DestinationName, country: Country, period: VisitPeriod, sourceFolder: SourceFolder): F[CreateResult] =
      albums.get(album).flatMap:
        case AlbumRepository.GetResult.Found(current) => createChapter(current, name, country, period, sourceFolder)
        case AlbumRepository.GetResult.NotFound       => CreateResult.AlbumNotFound.pure[F]
        case AlbumRepository.GetResult.Failed(_)      => CreateResult.Failed.pure[F]

    override def update(
        album: AlbumId,
        chapter: ChapterId,
        name: DestinationName,
        country: Country,
        period: VisitPeriod,
        sourceFolder: SourceFolder
    ): F[UpdateResult] =
      albums.get(album).flatMap:
        case AlbumRepository.GetResult.Found(current) => updateChapter(current, chapter, name, country, period, sourceFolder)
        case AlbumRepository.GetResult.NotFound       => UpdateResult.AlbumNotFound.pure[F]
        case AlbumRepository.GetResult.Failed(_)      => UpdateResult.Failed.pure[F]

    private def createChapter(album: Album, name: DestinationName, country: Country, period: VisitPeriod, sourceFolder: SourceFolder) =
      templates.listChapterStartTemplates.flatMap:
        case ListChapterStartTemplatesResult.Listed(available) =>
          buildChapter(name, country, period, sourceFolder, available.head).flatMap: chapter =>
            val candidate = withPeriod(album.year, chapter)
            val conflict  = if album.spreads.length >= 65 then ChapterConflict.AtCapacity.some else metadataConflict(album.chapters, candidate)
            conflict.fold(chapters.create(album.id, candidate).map(toCreateResult(_, candidate.id)))(reason => CreateResult.Rejected(reason).pure[F])
        case ListChapterStartTemplatesResult.Failed => CreateResult.Failed.pure[F]

    private def updateChapter(album: Album, id: ChapterId, name: DestinationName, country: Country, period: VisitPeriod, sourceFolder: SourceFolder) =
      album.chapters.find(_.id.value === id.value) match
        case None          => UpdateResult.Rejected(ChapterConflict.ChapterNotFound).pure[F]
        case Some(chapter) =>
          val destination = chapter.destination.copy(name = name, country = country)
          val candidate   = withPeriod(album.year, chapter.copy(destination = destination, period = period, sourceFolder = sourceFolder))
          val others      = album.chapters.filterNot(_.id.value === id.value)
          metadataConflict(others, candidate).fold(chapters.update(album.id, candidate).map(toUpdateResult))(reason =>
            UpdateResult.Rejected(reason).pure[F]
          )

    private def metadataConflict(others: Vector[Chapter], chapter: Chapter) =
      if chapter.destination.isHome && others.exists(_.destination.isHome) then ChapterConflict.HomeExists.some
      else if others.exists(_.sourceFolder.compare(chapter.sourceFolder) === 0) then ChapterConflict.FolderAssigned.some
      else None

    private def withPeriod(year: AlbumYear, chapter: Chapter) =
      if chapter.destination.isHome then
        val days: VisitDays = if Year.isLeap(year.toLong) then 366L else 365L
        chapter.copy(period = VisitPeriod(LocalDate.of(year, 1, 1), days))
      else chapter

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

    private def toCreateResult(result: SaveResult, id: ChapterId) = result match
      case SaveResult.Saved            => CreateResult.Created(id)
      case SaveResult.Rejected(reason) => CreateResult.Rejected(reason)
      case SaveResult.AlbumNotFound    => CreateResult.AlbumNotFound
      case SaveResult.Failed           => CreateResult.Failed

    private def toUpdateResult(result: SaveResult) = result match
      case SaveResult.Saved            => UpdateResult.Updated
      case SaveResult.Rejected(reason) => UpdateResult.Rejected(reason)
      case SaveResult.AlbumNotFound    => UpdateResult.AlbumNotFound
      case SaveResult.Failed           => UpdateResult.Failed

  enum CreateResult:
    case Created(id: ChapterId)
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed

  enum UpdateResult:
    case Updated
    case Rejected(reason: ChapterConflict)
    case AlbumNotFound, Failed
