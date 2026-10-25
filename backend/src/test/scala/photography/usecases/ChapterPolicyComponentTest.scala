package photography.usecases

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import munit.FunSuite
import photography.domain.album.*
import photography.domain.layout.*

import java.nio.file.Path
import java.time.{Instant, LocalDate}
import java.util.UUID

final class ChapterPolicyComponentTest extends FunSuite:
  test("should derive the entire album year including leap days"):
    val policy = ChapterPolicy.make

    val periods = Vector(policy.yearPeriod(2024), policy.yearPeriod(2026))

    assertEquals(periods, Vector(VisitPeriod(LocalDate.of(2024, 1, 1), 366), VisitPeriod(LocalDate.of(2026, 1, 1), 365)))

  test("should enforce interior capacity and Home and folder invariants"):
    val box     = SlotBox(0.0, 0.0, 100.0, 100.0)
    val country = Country(CountryId(id(1)), "RO", "Romania", AbsoluteLocation(Path.of("/flags/ro.svg")))
    val opening =
      Spread.AlbumStart(SpreadId(id(2)), SpreadTemplateId(id(3)), Vector.empty, SpreadSlot.Text(TextSpreadSlotId(id(4)), box), Vector.empty)
    val chapterOpening = Spread.ChapterStart(
      SpreadId(id(5)),
      SpreadTemplateId(id(6)),
      Vector.empty,
      SpreadSlot.Text(TextSpreadSlotId(id(7)), box),
      Vector.empty
    )
    val period  = VisitPeriod(LocalDate.of(2026, 5, 1), 2)
    val chapter =
      Chapter(ChapterId(id(8)), Destination(DestinationId(id(9)), "Kyoto", country), period, "", "/photos/Kyoto", chapterOpening, Vector.empty)
    val home   = chapter.copy(destination = chapter.destination.copy(name = "Home"), period = VisitPeriod(LocalDate.of(2026, 1, 1), 365))
    val second = chapter.copy(id = ChapterId(id(10)), sourceFolder = "/photos/second")
    val photo  = Spread.PhotosOnly(
      SpreadId(id(11)),
      SpreadTemplateId(id(12)),
      NonEmptyList.one(SpreadSlot.Photo(PhotoSpreadSlotId(id(13)), box, Set.empty)),
      Vector.empty
    )
    val album      = Album(AlbumId(id(14)), 2026, "", Instant.EPOCH, opening, Vector.empty)
    val atCapacity = album.copy(chapters = Vector(chapter.copy(spreads = Vector.fill(63)(photo))))
    val scenarios  = Vector(
      (album, Option.empty[ChapterConflict]),
      (album.copy(chapters = Vector(home)), Option.empty[ChapterConflict]),
      (atCapacity, Option.empty[ChapterConflict]),
      (atCapacity.copy(chapters = atCapacity.chapters :+ second), ChapterConflict.AtCapacity.some),
      (album.copy(chapters = Vector(home, home.copy(id = second.id))), ChapterConflict.HomeExists.some),
      (album.copy(chapters = Vector(home.copy(period = period))), ChapterConflict.HomePeriodMismatch.some),
      (album.copy(chapters = Vector(home.copy(period = VisitPeriod(LocalDate.of(2026, 1, 1), 366)))), ChapterConflict.HomePeriodMismatch.some),
      (album.copy(chapters = Vector(chapter, second.copy(sourceFolder = chapter.sourceFolder))), ChapterConflict.FolderAssigned.some)
    )
    val policy = ChapterPolicy.make

    val results  = scenarios.map { case (candidate, _) => policy.conflict(candidate) }
    val expected = scenarios.map { case (_, conflict) => conflict }

    assertEquals(results, expected)
    assertEquals(atCapacity.spreads.length, 65)

  private def id(value: Long) = new UUID(0L, value)
