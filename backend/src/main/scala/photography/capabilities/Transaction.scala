package photography.capabilities

trait Transaction[Db[_], F[_]]:
  def run[A](program: Db[A]): F[A]
  def resolve[A](program: Db[A]): F[A]

object Transaction:
  def apply[Db[_], F[_]](using transaction: Transaction[Db, F]): Transaction[Db, F] = transaction
