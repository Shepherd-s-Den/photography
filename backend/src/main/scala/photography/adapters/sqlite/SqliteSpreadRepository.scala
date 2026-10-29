package photography.adapters.sqlite

import cats.data.NonEmptyList
import cats.syntax.all.*
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits.*
import photography.adapters.sqlite.SqliteCodecs.given
import photography.domain.album.{Album, AlbumId, ChapterId, PhotoId}
import photography.domain.layout.*
import photography.ports.SpreadRepository

import java.util.UUID

object SqliteSpreadRepository:
  def make: SpreadRepository[ConnectionIO] = new LiveSqliteSpreadRepository

  final private class LiveSqliteSpreadRepository extends SpreadRepository[ConnectionIO]:
    override def loadAlbum(album: AlbumId): ConnectionIO[Option[Album]] = SqliteAlbumStore.load(album.value)

    override def insert(chapter: ChapterId, spread: Spread.PhotosOnly): ConnectionIO[Unit] =
      val insertSpread = sql"""INSERT INTO spread (id, template_id, kind, chapter_id, position)
        VALUES (${spread.id.value}, ${spread.template.value}, 'photos_only', ${chapter.value},
                (SELECT coalesce(max(position), 0) + 1 FROM spread WHERE chapter_id = ${chapter.value}))"""
      for
        _ <- insertSpread.update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ === 1)
        _ <- spread.photoSlots.traverse_(slot => SqliteAlbumStore.insertSlot(spread.id, slot))
      yield ()

    override def replace(spread: Spread): ConnectionIO[Unit] =
      val id       = spread.id.value
      val template = spread.template.value
      val rewrite  =
        for
          _ <- sql"UPDATE spread SET template_id = $template WHERE id = $id".update.run.ensure(new IllegalStateException(
            "Expected one persisted row"
          ))(_ === 1)
          _ <- sql"DELETE FROM placement WHERE spread_photo_slot_id IN (SELECT id FROM spread_photo_slot WHERE spread_id = $id)".update.run
          _ <- sql"DELETE FROM spread_photo_slot WHERE spread_id = $id".update.run
          _ <- sql"DELETE FROM spread_text_slot WHERE spread_id = $id".update.run
          _ <- photoSlotsOf(spread).traverse_(slot => SqliteAlbumStore.insertSlot(spread.id, slot))
          _ <- textSlotsOf(spread).traverse_(slot => SqliteAlbumStore.insertSlot(spread.id, slot))
          _ <- placementsOf(spread).traverse_(insertPlacement)
        yield ()
      rewrite

    override def reorder(chapter: ChapterId, order: NonEmptyList[SpreadId]): ConnectionIO[Unit] =
      val supplied = order.toList.map(_.value)
      for
        stored <- sql"SELECT id FROM spread WHERE chapter_id = ${chapter.value}".query[UUID].to[List]
        _      <- validateOrder(stored = stored, supplied = supplied).liftTo[ConnectionIO]
        _ <- sql"UPDATE spread SET position = position + $temporaryOffset WHERE chapter_id = ${chapter.value} AND kind = 'photos_only'".update.run
        _ <- supplied.zipWithIndex.traverse_ { case (id, position) =>
          sql"UPDATE spread SET position = $position WHERE id = $id".update.run.ensure(new IllegalStateException("Expected one persisted row"))(_ ===
            1).void
        }
      yield ()

    override def remove(spread: SpreadId): ConnectionIO[Unit] =
      sql"DELETE FROM spread WHERE id = ${spread.value}".update.run.ensure(new IllegalStateException("Expected one removed spread"))(_ === 1).void

    private def validateOrder(stored: List[UUID], supplied: List[UUID]) =
      val containsEverySpread = stored.size === supplied.size && stored.toSet === supplied.toSet
      Either.cond(containsEverySpread, (), new IllegalArgumentException("Order must contain every spread of the chapter exactly once"))

    private def insertPlacement(placement: SpreadPlacement) =
      val transform = placement.transform
      sql"""INSERT INTO placement (id, photo_id, kind, spread_photo_slot_id, cover_slot_id, shift_x, shift_y, mirrored, zoom, caption_corner)
        VALUES (${placement.id.value}, ${placement.photo.value}, 'spread', ${placement.slot.value}, NULL,
                ${transform.shift.x}, ${transform.shift.y}, ${transform.mirrored}, ${transform.zoom}, ${placement.captionCorner})""".update.run
        .ensure(new IllegalStateException("Expected one persisted row"))(_ === 1).void

    private def photoSlotsOf(spread: Spread) = spread match
      case value: Spread.AlbumStart   => value.photoSlots
      case value: Spread.ChapterStart => value.photoSlots
      case value: Spread.PhotosOnly   => value.photoSlots.toList.toVector

    private def textSlotsOf(spread: Spread) = spread match
      case value: Spread.AlbumStart   => Vector(value.textSlot)
      case value: Spread.ChapterStart => Vector(value.textSlot)
      case _: Spread.PhotosOnly       => Vector.empty[SpreadSlot.Text]

    private def placementsOf(spread: Spread) = spread.placements

    private val temporaryOffset = 1000000
