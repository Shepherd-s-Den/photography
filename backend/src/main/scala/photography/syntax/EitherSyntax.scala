package photography.syntax

import cats.data.EitherT

object EitherSyntax:
  extension [F[_], E, A](value: F[Either[E, A]])
    def eitherT: EitherT[F, E, A] = EitherT(value)
