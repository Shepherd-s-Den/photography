package photography.adapters.sqlite

import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.{Meta, Read, Write}
import photography.domain.layout.*

import java.util.UUID

private[sqlite] object SqliteCodecs:
  given Meta[UUID] = Meta[String].tiemap[UUID] { value =>
    Either.catchNonFatal(UUID.fromString(value)).leftMap(_.getMessage).flatMap: id =>
      Either.cond(id.toString === value, id, s"Noncanonical UUID: $value")
  }(_.toString)

  private given Meta[PositionOnSpread] =
    Meta[Double].tiemap[PositionOnSpread](_.refineEither)(value => value: Double)

  private given Meta[DistanceWithinSpread] =
    Meta[Double].tiemap[DistanceWithinSpread](_.refineEither)(value => value: Double)

  given Read[SlotBox] =
    Read[(PositionOnSpread, PositionOnSpread, DistanceWithinSpread, DistanceWithinSpread)].map(SlotBox.apply.tupled)

  given Write[SlotBox] =
    Write[(PositionOnSpread, PositionOnSpread, DistanceWithinSpread, DistanceWithinSpread)].contramap(box => (box.x, box.y, box.width, box.height))

  given Meta[SpreadEdge] = Meta[String].tiemap[SpreadEdge] {
    case "top"    => SpreadEdge.Top.asRight[String]
    case "bottom" => SpreadEdge.Bottom.asRight[String]
    case "left"   => SpreadEdge.Left.asRight[String]
    case "right"  => SpreadEdge.Right.asRight[String]
    case other    => s"Unknown bleed edge $other".asLeft[SpreadEdge]
  } {
    case SpreadEdge.Top    => "top"
    case SpreadEdge.Bottom => "bottom"
    case SpreadEdge.Left   => "left"
    case SpreadEdge.Right  => "right"
  }
