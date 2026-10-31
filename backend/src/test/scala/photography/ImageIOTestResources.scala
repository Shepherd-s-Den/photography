package photography

import cats.effect.{IO, Resource}
import java.util.concurrent.Semaphore

private[photography] object ImageIOTestResources:
  private val registryLock = new Semaphore(1, true)

  def acquire: Resource[IO, Unit] =
    Resource.make(IO.interruptible(registryLock.acquire()))(_ => IO.blocking(registryLock.release()))
