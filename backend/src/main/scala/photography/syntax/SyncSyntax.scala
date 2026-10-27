package photography.syntax

import cats.effect.Sync

object SyncSyntax:
  extension [A](thunk: => A)
    def blocking[F[_]: Sync]: F[A] = Sync[F].blocking(thunk)
