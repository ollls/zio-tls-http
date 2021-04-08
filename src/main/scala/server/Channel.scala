package zhttp

import nio.SocketAddress
import nio.channels.AsynchronousTlsByteChannel
import nio.channels.AsynchronousSocketChannel
import zio.{ Chunk, ZEnv, ZIO }

object Channel {

  final val HTTP_READ_PACKET = 16384

  def read(ch: Channel): ZIO[ZEnv, Exception, Chunk[Byte]] = ch match {
    case TcpChannel(c) => c.read2(HTTP_READ_PACKET)
    case TlsChannel(c ) => c.read
  }

  def readBuffer(ch: Channel, bb: java.nio.ByteBuffer): ZIO[ZEnv, Exception, Unit] = ch match {
    case  TcpChannel(c) => ???
    case  TlsChannel(c) => c.readBuffer(bb)
  }

  def write(ch: Channel, chunk: Chunk[Byte]): ZIO[ZEnv, Exception, Int] = ch match {
    case  TcpChannel(c) => c.write(chunk)
    case  TlsChannel(c) => c.write(chunk)
  }

  def close(ch: Channel): ZIO[ZEnv, Exception, Unit] = ch match {
    case TcpChannel(c) => c.close
    case TlsChannel(c) => c.close
  }

  def keepAlive(ch: Channel, ms: Long): Unit = ch match {
    case TcpChannel(c) => c.keepAlive(ms).asInstanceOf[Unit]
    case TlsChannel(c) => c.keepAlive(ms).asInstanceOf[Unit]
  }


  def remoteAddress( ch: Channel ): ZIO[ZEnv, Exception, Option[SocketAddress]] = ch match {
    case TcpChannel(c) => c.remoteAddress
    case TlsChannel(c) => c.remoteAddress

}
}

sealed trait Channel
case class TlsChannel(c: AsynchronousTlsByteChannel) extends Channel
case class TcpChannel(c: AsynchronousSocketChannel) extends Channel




