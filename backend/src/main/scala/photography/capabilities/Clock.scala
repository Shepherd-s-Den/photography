package photography.capabilities

import cats.effect.Clock as EffectClock

import java.time.Instant

trait Clock[F[_]]:
  def now: F[Instant]

object Clock:
  def apply[F[_]: Clock]: Clock[F] = summon[Clock[F]]

  def make[F[_]: EffectClock]: Clock[F] = LiveClock[F]

  final private class LiveClock[F[_]: EffectClock] extends Clock[F]:
    override def now: F[Instant] = EffectClock[F].realTimeInstant
