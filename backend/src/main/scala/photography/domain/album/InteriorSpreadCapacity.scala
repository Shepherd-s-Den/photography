package photography.domain.album

object InteriorSpreadCapacity:
  val maximum = 65

  def isReached(count: Int): Boolean = count >= maximum
