package photography.domain.album

import cats.syntax.all.*
import io.github.iltotore.iron.*
import munit.FunSuite

import java.time.LocalDate

final class WorkspaceValuesComponentTest extends FunSuite:
  test("should accept supported album years and generate their canonical titles"):
    val years = Vector(1900, 2026, 2100)

    val titles  = years.map(_.refineEither[AlbumYearConstraint].map(year => AlbumYear.title(year): String))
    val invalid = Vector(1899, 2101).map(_.refineEither[AlbumYearConstraint].isLeft)

    assertEquals(titles, years.map(year => s"Journey of the Shepherds $year".asRight[String]))
    assertEquals(invalid, Vector(true, true))

  test("should preserve inclusive same-day and cross-year vacation dates as exclusive UTC periods"):
    val cases = Vector("2026-04-02" -> "2026-04-02", "2025-12-28" -> "2026-01-04")

    cases.foreach { case (first, last) =>
      val start = LocalDate.parse(first)
      val end   = LocalDate.parse(last)

      val period = VisitPeriod.fromDates(start, end)

      assertEquals(period.map(_.start.toString), s"${first}T00:00:00Z".asRight[String])
      assertEquals(period.map(_.end.toString), s"${end.plusDays(1)}T00:00:00Z".asRight[String])
      assertEquals(period.map(_.firstDay), start.asRight[String])
      assertEquals(period.map(_.lastDay), end.asRight[String])
    }

    assert(VisitPeriod.fromDates(LocalDate.parse("2026-04-03"), LocalDate.parse("2026-04-02")).isLeft)
    assert(VisitPeriod.fromDates(LocalDate.MAX, LocalDate.MAX).isLeft)

  test("should derive Home from the full album year including leap day"):
    val years = Vector(1900, 2024, 2100)

    val periods = years.map(year => VisitPeriod.wholeYear(year.refineUnsafe[AlbumYearConstraint]))

    assertEquals(periods.map(_.firstDay.toString), years.map(year => s"$year-01-01"))
    assertEquals(periods.map(_.lastDay.toString), years.map(year => s"$year-12-31"))
    assertEquals(periods.map(_.end.toString), years.map(year => s"${year + 1}-01-01T00:00:00Z"))

  test("should expose no unchecked visit-period construction or copy"):
    val construction = compileErrors("VisitPeriod(java.time.Instant.EPOCH, java.time.Instant.EPOCH)")
    val copying      = compileErrors("VisitPeriod.wholeYear(2026).copy(end = java.time.Instant.EPOCH)")

    assert(construction.nonEmpty)
    assert(copying.nonEmpty)

  test("should trim chapter names and reject blank names"):
    val names = Vector("  South Morocco  ", "Kyoto", "", "   ")

    val accepted = names.map(ChapterName.from)

    assertEquals(accepted.take(2).map(_.map(value => value: String)), Vector("South Morocco".asRight[String], "Kyoto".asRight[String]))
    assertEquals(accepted.drop(2).map(_.isLeft), Vector(true, true))

  test("should accept only nonempty contained relative photo folders"):
    val valid   = Vector("2026/selection/Home", "2026/selection/South Morocco", "not-yet-created")
    val invalid = Vector("", " ", "/photos/Home", "../Home", "2026/../Home", "C:/photos/Home", "a\\..\\b", ".", "a\u0000b")

    val folders  = valid.map(value => RelativeLocation.from(value).map(_.value.toString))
    val rejected = invalid.map(value => RelativeLocation.from(value).isLeft)

    assertEquals(folders, valid.map(_.asRight[String]))
    assertEquals(rejected, invalid.map(_ => true))

  test("should permit a 21st chapter position without imposing a chapter ceiling"):
    val positions = Vector(0, 19, 20, 64, 100)

    val valid    = positions.map(_.refineEither[PositionInAlbumConstraint].isRight)
    val negative = (-1).refineEither[PositionInAlbumConstraint]

    assertEquals(valid, positions.map(_ => true))
    assert(negative.isLeft)
