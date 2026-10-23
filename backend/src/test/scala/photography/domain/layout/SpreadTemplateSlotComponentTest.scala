package photography.domain.layout

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*
import munit.FunSuite

final class SpreadTemplateSlotComponentTest extends FunSuite:

  test("should order slots by kind and geometry"):
    val firstPhoto = SpreadTemplateSlot.Photo(buildBox(xValue = 0.0))
    val laterPhoto = SpreadTemplateSlot.Photo(buildBox(xValue = 0.5))
    val text       = SpreadTemplateSlot.Text(buildBox(xValue = 0.0))
    val order      = summon[cats.Order[SpreadTemplateSlot]]

    assert(order.compare(firstPhoto, laterPhoto) < 0)
    assert(order.compare(laterPhoto, text) < 0)

  private def buildBox(xValue: Double): SlotBox =
    val x      = xValue.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val y      = 0.0.refineUnsafe[GreaterEqual[0.0] & Less[1.7976931348623157e308]]
    val width  = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    val height = 1.0.refineUnsafe[Positive & Less[1.7976931348623157e308]]
    SlotBox(x, y, width, height)
