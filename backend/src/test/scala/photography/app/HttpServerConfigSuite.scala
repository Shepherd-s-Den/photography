package photography.app

import munit.FunSuite

import java.net.{Inet4Address, Inet6Address, NetworkInterface}
import scala.jdk.CollectionConverters.*

final class HttpServerConfigSuite extends FunSuite:

  private def config(environment: Map[String, String]): HttpServerConfig =
    HttpServerConfig.fromEnvironment(environment) match
      case Right(value) => value
      case Left(error)  => fail(error.getMessage)

  test("defaults to loopback and the standard port"):
    val actual = config(Map.empty)

    assertEquals(actual.host.toString, "127.0.0.1")
    assertEquals(actual.port, 8080)

  test("an empty host uses the loopback default"):
    val actual = config(Map("PHOTOGRAPHY_HOST" -> ""))

    assertEquals(actual.host.toString, "127.0.0.1")

  test("accepts a local host name and both explicit wildcard addresses"):
    val localHost    = config(Map("PHOTOGRAPHY_HOST" -> "localhost"))
    val ipv4Wildcard = config(Map("PHOTOGRAPHY_HOST" -> "0.0.0.0"))
    val ipv6Wildcard = config(Map("PHOTOGRAPHY_HOST" -> "::"))

    assert(Set("127.0.0.1", "::1").contains(localHost.host.toString))
    assertEquals(ipv4Wildcard.host.toString, "0.0.0.0")
    assertEquals(ipv6Wildcard.host.toString, "::")

  test("accepts a non-loopback address assigned to a local network interface"):
    val localAddress = NetworkInterface
      .getNetworkInterfaces
      .asScala
      .flatMap(_.getInetAddresses.asScala)
      .collectFirst {
        case address: Inet4Address if !address.isLoopbackAddress && !address.isAnyLocalAddress =>
          address.getHostAddress
      }
      .fold(fail("a non-loopback local network address is required"))(identity)

    val actual = config(Map("PHOTOGRAPHY_HOST" -> localAddress))

    assertEquals(actual.host.toString, localAddress)

  test("rejects an address with a scope identifier that ip4s cannot represent"):
    val scopedAddress = Inet6Address.getByAddress("scoped", Array.fill[Byte](16)(0), 1)
    val actual        = HttpServerConfig.asIpAddress("host", scopedAddress)

    assert(actual.isLeft)

  test("rejects URL syntax, unresolved hosts, and addresses not assigned locally"):
    val url        = HttpServerConfig.fromEnvironment(Map("PHOTOGRAPHY_HOST" -> "http://127.0.0.1"))
    val unresolved = HttpServerConfig.fromEnvironment(Map("PHOTOGRAPHY_HOST" -> "missing.invalid"))
    val nonLocal   = HttpServerConfig.fromEnvironment(Map("PHOTOGRAPHY_HOST" -> "192.0.2.1"))

    assert(url.isLeft)
    assert(unresolved.isLeft)
    assert(nonLocal.isLeft)

  test("accepts the inclusive port boundaries"):
    val first = config(Map("PHOTOGRAPHY_PORT" -> "1"))
    val last  = config(Map("PHOTOGRAPHY_PORT" -> "65535"))

    assertEquals(first.port, 1)
    assertEquals(last.port, 65535)

  test("rejects malformed and out-of-range ports"):
    val malformed = HttpServerConfig.fromEnvironment(Map("PHOTOGRAPHY_PORT" -> "8O80"))
    val empty     = HttpServerConfig.fromEnvironment(Map("PHOTOGRAPHY_PORT" -> ""))
    val tooLow    = HttpServerConfig.fromEnvironment(Map("PHOTOGRAPHY_PORT" -> "0"))
    val tooHigh   = HttpServerConfig.fromEnvironment(Map("PHOTOGRAPHY_PORT" -> "65536"))

    assert(malformed.isLeft)
    assert(empty.isLeft)
    assert(tooLow.isLeft)
    assert(tooHigh.isLeft)
