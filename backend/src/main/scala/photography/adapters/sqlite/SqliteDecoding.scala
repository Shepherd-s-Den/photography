package photography.adapters.sqlite

import cats.syntax.all.*
import io.github.iltotore.iron.*
import org.typelevel.doobie.ConnectionIO
import photography.domain.layout.{DistanceWithinSpread, PositionOnSpread, SlotBox}

import java.util.UUID
import scala.util.control.NoStackTrace

private[sqlite] object SqliteDecoding:
  def uuid(value: String): Either[String, UUID] =
    Either.catchNonFatal(UUID.fromString(value)).leftMap(_.getMessage).flatMap: id =>
      Either.cond(id.toString === value, id, s"Noncanonical UUID: $value")

  def box(x: Double, y: Double, width: Double, height: Double): Either[String, SlotBox] =
    for
      validX      <- x.refineEither: Either[String, PositionOnSpread]
      validY      <- y.refineEither: Either[String, PositionOnSpread]
      validWidth  <- width.refineEither: Either[String, DistanceWithinSpread]
      validHeight <- height.refineEither: Either[String, DistanceWithinSpread]
    yield SlotBox(validX, validY, validWidth, validHeight)

  def requireData[A](value: Either[String, A]): ConnectionIO[A] =
    value.leftMap(InvalidSqliteData(_)).liftTo[ConnectionIO]

final private[sqlite] case class InvalidSqliteData(reason: String) extends RuntimeException(reason) with NoStackTrace
