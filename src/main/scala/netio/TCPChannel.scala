package zhttp.netio

import java.nio.channels.{AsynchronousChannelGroup, AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}

import java.util.concurrent.TimeUnit
import java.nio.channels.Channel
import zio.{ZIO, Task}
import java.nio.ByteBuffer
import zio.Chunk
import java.net.StandardSocketOptions
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.Executors

object TCPChannel {

  val HTTP_READ_PACKET = 16384

  /////////////////////////////////
  def effectAsyncChannel[C <: Channel, A](
      ch: C
  )(op: C => CompletionHandler[A, Any] => Unit): Task[A] = {
    ZIO.asyncZIO[Any, Throwable, A](cb =>
      ZIO
        .attempt(op(ch))
        .flatMap(handler =>
          ZIO.attempt(handler(new CompletionHandler[A, Any] {
            def completed(result: A, u: Any): Unit = {
              // println( "completed")
              cb(ZIO.succeed(result))
            }
            def failed(t: Throwable, u: Any): Unit = {
              // println( "failed")
              t match {
                case e: Exception => cb(ZIO.fail(e))
                case _            => cb(ZIO.die(t))
              }
            }
          }))
        )
    )
  }

  def accept(
      sch: AsynchronousServerSocketChannel
  ): Task[TCPChannel] =
    effectAsyncChannel[
      AsynchronousServerSocketChannel,
      AsynchronousSocketChannel
    ](sch)(c => h => { c.accept(null, h) }).map(new TCPChannel(_))

  def connect(
      host: String,
      port: Int,
      group: AsynchronousChannelGroup = null
  ): Task[TCPChannel] = {
    val T = for {
      address <- ZIO.attempt(new InetSocketAddress(host, port))
      ch <-
        if (group == null) ZIO.attempt(AsynchronousSocketChannel.open())
        else ZIO.attempt(AsynchronousSocketChannel.open(group))
      _ <- effectAsyncChannel[AsynchronousSocketChannel, Void](ch)(ch => ch.connect(address, (), _))
    } yield (ch)
    T.map(c => new TCPChannel(c))
  }

  def bind(
      addr: InetSocketAddress,
      socketGroupThreadsNum: Int
  ): Task[AsynchronousServerSocketChannel] =
    for {
      group <- ZIO.attempt(
        AsynchronousChannelGroup.withThreadPool(
          Executors.newFixedThreadPool(socketGroupThreadsNum)
        )
      )
      channel <- ZIO.attempt(
        group.provider().openAsynchronousServerSocketChannel(group)
      )
      bind <- ZIO.attempt(channel.bind(addr))
    } yield (bind)

}

class TCPChannel(val ch: AsynchronousSocketChannel) extends IOChannel {
  // testing with big picture BLOBS
  // ch.setOption(StandardSocketOptions.SO_RCVBUF, 6 * 1024 * 1024);
  // ch.setOption(StandardSocketOptions.SO_SNDBUF, 6 * 1024 * 1024);
  // ch.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
  var f_putBack: ByteBuffer = null

  private[zhttp] def put(bb: ByteBuffer): Task[Unit] = ZIO.attempt { f_putBack = bb }

  def readBuffer(
      dst: ByteBuffer,
      timeOut: Int
  ): Task[Int] = {
    for {
      _ <-
        if (f_putBack != null) {
          ZIO.attempt(dst.put(f_putBack)) *> ZIO.attempt { f_putBack = null }
        } else ZIO.unit

      n <- TCPChannel.effectAsyncChannel[AsynchronousSocketChannel, Integer](
        ch
      )(c => c.read(dst, timeOut, TimeUnit.MILLISECONDS, (), _))

      _ <- ZIO
        .fail(new java.nio.channels.ClosedChannelException)
        .when(n.intValue() < 0)

    } yield (n.intValue())
  }

  def rcvBufSize(nBytes: Int) = {
    ch.setOption[Integer](StandardSocketOptions.SO_RCVBUF, nBytes)
  }

  def sndBufSize(nBytes: Int) = {
    ch.setOption[Integer](StandardSocketOptions.SO_SNDBUF, nBytes)
  }

  def setOption[T](opt: java.net.SocketOption[T], val0: T) =
    ch.setOption(opt, val0)

  def read(
      timeOut: Int
  ): Task[Chunk[Byte]] = {
    for {
      bb <- ZIO.attempt(ByteBuffer.allocate(TCPChannel.HTTP_READ_PACKET))

      n <- TCPChannel.effectAsyncChannel[AsynchronousSocketChannel, Integer](
        ch
      )(c => c.read(bb, timeOut, TimeUnit.MILLISECONDS, (), _))

      _ <- ZIO.fail(new java.nio.channels.ClosedChannelException).when(n < 0)

      chunk <- ZIO.attempt(Chunk.fromByteBuffer(bb.flip))

    } yield (chunk)
  }

  def write(chunk: Chunk[Byte]): Task[Int] = {
    val bb = ByteBuffer.wrap(chunk.toArray)
    write(bb)
  }

  def write(buffer: ByteBuffer): Task[Int] = {
    TCPChannel
      .effectAsyncChannel[AsynchronousSocketChannel, Integer](ch)(c => ch.write(buffer, (), _))
      .map(_.intValue)
      .repeatWhile(_ => buffer.remaining() > 0)
  }

  def close(): Task[Unit] = ZIO.attempt(ch.close)

  def remoteAddress(): Task[SocketAddress] = ZIO.attempt(ch.getRemoteAddress())

}
