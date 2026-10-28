package photography.syntax

import cats.data.EitherT
import cats.{Applicative, Functor}

object EitherSyntax:
  extension [F[_], E, A](value: F[Either[E, A]])
    def eitherT: EitherT[F, E, A] = EitherT(value)

  extension [F[_]: Functor, A](value: F[A])
    def lift[E]: EitherT[F, E, A] = EitherT.liftF(value)

  object Pure:
    extension [E, A](value: Either[E, A])
      def eitherT[F[_]: Applicative]: EitherT[F, E, A] = EitherT.fromEither[F](value)
