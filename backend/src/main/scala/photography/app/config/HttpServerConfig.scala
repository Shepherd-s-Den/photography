package photography.app

import cats.syntax.all.*
import com.comcast.ip4s.IpAddress

import java.net.{InetAddress, NetworkInterface, UnknownHostException}

final case class HttpServerConfig(host: IpAddress, port: Int)

final case class HttpServerConfigError(message: String) extends IllegalArgumentException(message)

object HttpServerConfig:

  private val defaultHost = "127.0.0.1"
  private val defaultPort = 8080

  def fromEnvironment(environment: Map[String, String]): Either[HttpServerConfigError, HttpServerConfig] =
    for
      port <- parsePort(environment)
      host <- resolveHost(environment)
    yield HttpServerConfig(host, port)

  private def parsePort(environment: Map[String, String]): Either[HttpServerConfigError, Int] =
    environment.get("PHOTOGRAPHY_PORT") match
      case None        => defaultPort.asRight[HttpServerConfigError]
      case Some(value) =>
        value.toIntOption
          .filter(port => port >= 1 && port <= 65535)
          .toRight(HttpServerConfigError("PHOTOGRAPHY_PORT must be an integer in 1..65535"))

  private def resolveHost(environment: Map[String, String]): Either[HttpServerConfigError, IpAddress] =
    val host = environment.get("PHOTOGRAPHY_HOST").filter(_.nonEmpty).getOrElse(defaultHost)
    if host.contains("://") then
      HttpServerConfigError("PHOTOGRAPHY_HOST must be a bind address, not a URL").asLeft[IpAddress]
    else
      val resolved =
        try InetAddress.getAllByName(host).toList.asRight[HttpServerConfigError]
        catch
          case _: UnknownHostException =>
            HttpServerConfigError(s"PHOTOGRAPHY_HOST '$host' could not be resolved")
              .asLeft[List[InetAddress]]

      resolved.flatMap(findLocalAddress(host, _)).flatMap(asIpAddress(host, _))

  private def findLocalAddress(
      host: String,
      addresses: List[InetAddress]
  ): Either[HttpServerConfigError, InetAddress] =
    addresses
      .foldLeft[Either[HttpServerConfigError, Option[InetAddress]]](
        Option.empty[InetAddress].asRight[HttpServerConfigError]
      ):
        case (found, address) =>
          found.flatMap:
            case some @ Some(_) => some.asRight[HttpServerConfigError]
            case None           => Option.when(isLocalAddress(address))(address).asRight[HttpServerConfigError]
      .flatMap(_.toRight(HttpServerConfigError(s"PHOTOGRAPHY_HOST '$host' is not assigned to this machine")))

  private def isLocalAddress(
      address: InetAddress
  ): Boolean =
    address.isLoopbackAddress ||
      address.isAnyLocalAddress ||
      Option(NetworkInterface.getByInetAddress(address)).isDefined

  private[app] def asIpAddress(
      host: String,
      address: InetAddress
  ): Either[HttpServerConfigError, IpAddress] =
    IpAddress
      .fromString(address.getHostAddress)
      .fold(
        HttpServerConfigError(s"PHOTOGRAPHY_HOST '$host' is not a valid IP address").asLeft[IpAddress]
      )(_.asRight[HttpServerConfigError])
