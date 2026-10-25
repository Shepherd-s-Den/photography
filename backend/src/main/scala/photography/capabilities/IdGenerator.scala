package photography.capabilities

import cats.effect.Sync

import java.util.UUID

trait IdGenerator[F[_]]:
  def nextId: F[UUID]

object IdGenerator:
  def apply[F[_]: IdGenerator]: IdGenerator[F] = summon[IdGenerator[F]]

  def make[F[_]: Sync]: IdGenerator[F] = LiveIdGenerator[F]

  final private class LiveIdGenerator[F[_]: Sync] extends IdGenerator[F]:
    override def nextId: F[UUID] = Sync[F].delay(UUID.randomUUID())
