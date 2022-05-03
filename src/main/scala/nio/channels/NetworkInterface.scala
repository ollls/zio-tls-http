
package nio

import java.net.{ SocketException, NetworkInterface => JNetworkInterface }

import zio.{IO,ZIO}

import scala.jdk.CollectionConverters._

class NetworkInterface private[nio] (private[nio] val jNetworkInterface: JNetworkInterface) {

  import NetworkInterface.JustSocketException

  def name: String = jNetworkInterface.getName

  def inetAddresses: Iterator[InetAddress] =
    jNetworkInterface.getInetAddresses.asScala.map(new InetAddress(_))

  def interfaceAddresses: List[InterfaceAddress] =
    jNetworkInterface.getInterfaceAddresses.asScala.map(new InterfaceAddress(_)).toList

  def subInterfaces: Iterator[NetworkInterface] =
    jNetworkInterface.getSubInterfaces.asScala.map(new NetworkInterface(_))

  def parent: NetworkInterface = new NetworkInterface(jNetworkInterface.getParent)

  def index: Int = jNetworkInterface.getIndex

  def displayName: String = jNetworkInterface.getDisplayName

  val isUp: IO[SocketException, Boolean] =
    ZIO.attempt(jNetworkInterface.isUp).refineOrDie(JustSocketException)

  val isLoopback: IO[SocketException, Boolean] =
    ZIO.attempt(jNetworkInterface.isLoopback).refineOrDie(JustSocketException)

  val isPointToPoint: IO[SocketException, Boolean] =
    ZIO.attempt(jNetworkInterface.isPointToPoint).refineOrDie(JustSocketException)

  val supportsMulticast: IO[SocketException, Boolean] =
    ZIO.attempt(jNetworkInterface.supportsMulticast).refineOrDie(JustSocketException)

  val hardwareAddress: IO[SocketException, Array[Byte]] =
    ZIO.attempt(jNetworkInterface.getHardwareAddress).refineOrDie(JustSocketException)

  val mtu: IO[SocketException, Int] =
    ZIO.attempt(jNetworkInterface.getMTU).refineOrDie(JustSocketException)

  def isVirtual: Boolean = jNetworkInterface.isVirtual

}

object NetworkInterface {

  val JustSocketException: PartialFunction[Throwable, SocketException] = {
    case e: SocketException => e
  }

  def byName(name: String): IO[SocketException, NetworkInterface] =
    ZIO.attempt(JNetworkInterface.getByName(name))
      .refineOrDie(JustSocketException)
      .map(new NetworkInterface(_))

  def byIndex(index: Integer): IO[SocketException, NetworkInterface] =
    ZIO.attempt(JNetworkInterface.getByIndex(index))
      .refineOrDie(JustSocketException)
      .map(new NetworkInterface(_))

  def byInetAddress(address: InetAddress): IO[SocketException, NetworkInterface] =
    ZIO.attempt(JNetworkInterface.getByInetAddress(address.jInetAddress))
      .refineOrDie(JustSocketException)
      .map(new NetworkInterface(_))

  def networkInterfaces: IO[SocketException, Iterator[NetworkInterface]] =
    ZIO.attempt(JNetworkInterface.getNetworkInterfaces.asScala)
      .refineOrDie(JustSocketException)
      .map(_.map(new NetworkInterface(_)))
}
