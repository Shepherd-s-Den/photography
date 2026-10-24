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

  given Meta[SpreadTemplateKind] = Meta[String].tiemap[SpreadTemplateKind] {
    case "album_start"   => SpreadTemplateKind.AlbumStart.asRight[String]
    case "chapter_start" => SpreadTemplateKind.ChapterStart.asRight[String]
    case "photos_only"   => SpreadTemplateKind.PhotosOnly.asRight[String]
    case other           => s"Unknown spread kind $other".asLeft[SpreadTemplateKind]
  } {
    case SpreadTemplateKind.AlbumStart   => "album_start"
    case SpreadTemplateKind.ChapterStart => "chapter_start"
    case SpreadTemplateKind.PhotosOnly   => "photos_only"
  }

  private given Meta[PositionOnSpread] =
    Meta[Double].tiemap[PositionOnSpread](_.refineEither)(value => value: Double)

  private given Meta[DistanceWithinSpread] =
    Meta[Double].tiemap[DistanceWithinSpread](_.refineEither)(value => value: Double)

  given Read[SlotBox] =
    Read[(PositionOnSpread, PositionOnSpread, DistanceWithinSpread, DistanceWithinSpread)].map(SlotBox.apply.tupled)

  given Write[SlotBox] =
    Write[(PositionOnSpread, PositionOnSpread, DistanceWithinSpread, DistanceWithinSpread)].contramap(box => (box.x, box.y, box.width, box.height))
