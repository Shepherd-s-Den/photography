package photography.usecases

import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import photography.domain.album.*

import java.time.{LocalDate, Year}

trait ChapterPolicy:
  def yearPeriod(year: AlbumYear): VisitPeriod
  def conflict(album: Album): Option[ChapterConflict]

object ChapterPolicy:
  def make: ChapterPolicy = new LiveChapterPolicy

  final private class LiveChapterPolicy extends ChapterPolicy:
    override def yearPeriod(year: AlbumYear): VisitPeriod =
      val days: VisitDays = if Year.isLeap(year.toLong) then 366L else 365L
      VisitPeriod(LocalDate.of(year, 1, 1), days)

    override def conflict(album: Album): Option[ChapterConflict] =
      val chapters = album.chapters
      val home     = chapters.filter(_.destination.isHome)
      if album.spreads.length > 65 then ChapterConflict.AtCapacity.some
      else if home.sizeIs > 1 then ChapterConflict.HomeExists.some
      else if home.exists(chapter => !samePeriod(chapter.period, yearPeriod(album.year))) then ChapterConflict.HomePeriodMismatch.some
      else if chapters.map(_.sourceFolder).distinct.size =!= chapters.size then ChapterConflict.FolderAssigned.some
      else None

    private def samePeriod(left: VisitPeriod, right: VisitPeriod) =
      left.firstDay.compareTo(right.firstDay) === 0 && left.days.compare(right.days) === 0
