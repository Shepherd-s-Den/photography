package photography.capabilities

trait Transaction[F[_]]:
  def atomically[E, A](operation: F[Transaction.Decision[E, A]]): F[Transaction.Result[E, A]]

object Transaction:
  def apply[F[_]: Transaction]: Transaction[F] = summon[Transaction[F]]

  enum Decision[+E, +A]:
    case Commit(value: A)
    case Reject(reason: E)

  enum Result[+E, +A]:
    case Committed(value: A)
    case Rejected(reason: E)
    case Failed
