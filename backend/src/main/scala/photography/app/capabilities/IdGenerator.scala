package photography.app.capabilities

import cats.effect.Sync

import java.util.UUID

trait IdGenerator[F[_]]:
  def nextId: F[UUID]

object IdGenerator:
  def make[F[_]: Sync]: IdGenerator[F] = LiveIdGenerator[F]

  final private class LiveIdGenerator[F[_]: Sync] extends IdGenerator[F]:
    override def nextId: F[UUID] = Sync[F].delay(UUID.randomUUID())
