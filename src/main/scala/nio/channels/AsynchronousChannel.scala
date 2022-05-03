package nio.channels

import java.io.IOException
import java.lang.{ Integer => JInteger, Long => JLong, Void => JVoid }
import java.nio.channels.{
  AsynchronousByteChannel => JAsynchronousByteChannel,
  AsynchronousServerSocketChannel => JAsynchronousServerSocketChannel,
  AsynchronousSocketChannel => JAsynchronousSocketChannel,
  CompletionHandler => JCompletionHandler
}
import java.nio.{ ByteBuffer => JByteBuffer }
import java.util.concurrent.TimeUnit


import nio.channels.AsynchronousChannel._
import nio.{ Buffer, SocketAddress, SocketOption }
import zio._

class AsynchronousByteChannel(private val channel: JAsynchronousByteChannel) {

  /**
   *  Reads data from this channel into buffer, returning the number of bytes
   *  read, or -1 if no bytes were read.
   */
  final private[nio] def readBuffer(b: Buffer[Byte]): IO[Exception, Int] =
    wrap[JInteger](h => channel.read(b.buffer.asInstanceOf[JByteBuffer], (), h)).map(_.toInt)

  final def read(capacity: Int): IO[Exception, Chunk[Byte]] =
    for {
      b <- Buffer.byte(capacity)
      l <- readBuffer(b)
      a <- b.array
      r <- if (l == -1) {
            ZIO.fail(new IOException("Connection reset by peer"))
          } else {
            ZIO.succeed(Chunk.fromArray(a).take(l))
          }
    } yield r

  /**
   *  Writes data into this channel from buffer, returning the number of bytes written.
   */
  final private[nio] def writeBuffer(b: Buffer[Byte]): IO[Exception, Int] =
    wrap[JInteger](h => channel.write(b.buffer.asInstanceOf[JByteBuffer], (), h)).map(_.toInt)

  final def write(chunk: Chunk[Byte]): IO[Exception, Int] =
    for {
      b <- Buffer.byte(chunk)
      r <- writeBuffer(b)
    } yield r

  /**
   * Closes this channel.
   */
  final val close: IO[Exception, Unit] =
    ZIO.attempt(channel.close()).refineToOrDie[Exception]

  /**
   * Tells whether or not this channel is open.
   */
  final val isOpen: UIO[Boolean] =
    ZIO.succeed(channel.isOpen)
}

class AsynchronousServerSocketChannel(private val channel: JAsynchronousServerSocketChannel) {

  /**
   * Binds the channel's socket to a local address and configures the socket
   * to listen for connections.
   */
  final def bind(address: SocketAddress): IO[Exception, Unit] =
    ZIO.attempt(channel.bind(address.jSocketAddress)).refineToOrDie[Exception].unit

  /**
   * Binds the channel's socket to a local address and configures the socket
   * to listen for connections, up to backlog pending connection.
   */
  final def bind(address: SocketAddress, backlog: Int): IO[Exception, Unit] =
    ZIO.attempt(channel.bind(address.jSocketAddress, backlog)).refineToOrDie[Exception].unit

  final def setOption[T](name: SocketOption[T], value: T): IO[Exception, Unit] =
    ZIO.attempt(channel.setOption(name.jSocketOption, value)).refineToOrDie[Exception].unit

  /**
   * Accepts a connection.
   */
  final val accept : ZIO[Scope, Exception, AsynchronousSocketChannel] =
    ZIO.acquireRelease(
      wrap[JAsynchronousSocketChannel](h => channel.accept((), h))
        .map(AsynchronousSocketChannel(_))
    )(_.close.orDie)


  final val accept2: IO[Exception, AsynchronousSocketChannel] =
      wrap[JAsynchronousSocketChannel](h => channel.accept((), h))
        .map(AsynchronousSocketChannel(_))      



  /**
   * The `SocketAddress` that the socket is bound to,
   * or the `SocketAddress` representing the loopback address if
   * denied by the security manager, or `Maybe.empty` if the
   * channel's socket is not bound.
   */
  final def localAddress: IO[Exception, Option[SocketAddress]] =
    ZIO.attempt(
        Option(channel.getLocalAddress).map(new SocketAddress(_))
      )
      .refineToOrDie[Exception]

  /**
   * Closes this channel.
   */
  final private[channels] val close: IO[Exception, Unit] =
    ZIO.attempt(channel.close()).refineToOrDie[Exception]

  /**
   * Tells whether or not this channel is open.
   */
  final val isOpen: UIO[Boolean] =
    ZIO.succeed(channel.isOpen)
}

object AsynchronousServerSocketChannel {

  def apply() : ZIO[Scope, Exception, AsynchronousServerSocketChannel] = {
    val open = ZIO
      .attempt(JAsynchronousServerSocketChannel.open())
      .refineToOrDie[Exception]
      .map(new AsynchronousServerSocketChannel(_))

    ZIO.acquireRelease(open)(_.close.orDie)
  }

  def apply(
    channelGroup: AsynchronousChannelGroup
  ) : ZIO[Scope, Exception, AsynchronousServerSocketChannel]= {
    val open = ZIO.attempt(
        JAsynchronousServerSocketChannel.open(channelGroup.channelGroup)
      )
      .refineOrDie {
        case e: Exception => e
      }
      .map(new AsynchronousServerSocketChannel(_))

    ZIO.acquireRelease(open)(_.close.orDie)
  }
}

class AsynchronousSocketChannel(private val channel: JAsynchronousSocketChannel)
    extends AsynchronousByteChannel(channel) {

    //used for Keep-Alive, if HTTPS
  var READ_TIMEOUT_MS: Long = 5000
  final def keepAlive(ms: Long) = { READ_TIMEOUT_MS = ms; this }
  //prealoc carryover buffer, position getting saved between calls
  //val IN_J_BUFFER = java.nio.ByteBuffer.allocate( 16000 * 2)


  final def read2(capacity: Int) : IO[Exception, Chunk[Byte]] =
    for {
      in  <- Buffer.byte( capacity )
      l <- readBuffer( in, Duration(READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))
      a <- in.array
      r <- if (l == -1) {
            ZIO.fail(new IOException("Connection reset by peer"))
          } else {
            ZIO.succeed(Chunk.fromArray(a).take(l))
          }
    } yield r    

  final def bind(address: SocketAddress): IO[Exception, Unit] =
    ZIO.attempt(channel.bind(address.jSocketAddress)).refineToOrDie[Exception].unit

  final def setOption[T](name: SocketOption[T], value: T): IO[Exception, Unit] =
    ZIO.attempt(channel.setOption(name.jSocketOption, value)).refineToOrDie[Exception].unit

  final def shutdownInput: IO[Exception, Unit] =
    ZIO.attempt(channel.shutdownInput()).refineToOrDie[Exception].unit

  final def shutdownOutput: IO[Exception, Unit] =
    ZIO.attempt(channel.shutdownOutput()).refineToOrDie[Exception].unit

  final def remoteAddress: IO[Exception, Option[SocketAddress]] =
    ZIO.attempt(
        Option(channel.getRemoteAddress)
          .map(new SocketAddress(_))
      )
      .refineToOrDie[Exception]

  final def localAddress: IO[Exception, Option[SocketAddress]] =
    ZIO.attempt(
        Option(channel.getLocalAddress)
          .map(new SocketAddress(_))
      )
      .refineToOrDie[Exception]

  final def connect(socketAddress: SocketAddress): IO[Exception, Unit] =
    wrap[JVoid](h => channel.connect(socketAddress.jSocketAddress, (), h)).unit

  final def connect(socketAddress: java.net.SocketAddress): IO[Exception, Unit] =
    wrap[JVoid](h => channel.connect( socketAddress, (), h)).unit  

  final private[nio] def readBuffer[A](dst: Buffer[Byte], timeout: Duration): IO[Exception, Int] =
    wrap[JInteger] { h =>
      channel.read(
        dst.buffer.asInstanceOf[JByteBuffer],
        timeout.toNanos(),
        /*timeout.fold(Long.MaxValue, _.toNanos)*/
        TimeUnit.NANOSECONDS,
        (),
        h
      )
    }.map(_.toInt)

  final def read[A](capacity: Int, timeout: Duration): IO[Exception, Chunk[Byte]] =
    for {
      b <- Buffer.byte(capacity)
      l <- readBuffer(b, timeout)
      a <- b.array
      r <- if (l == -1) {
            ZIO.fail(new IOException("Connection reset by peer"))
          } else {
            ZIO.succeed(Chunk.fromArray(a).take(l))
          }
    } yield r

  final private[nio] def readBuffer[A](
    dsts: List[Buffer[Byte]],
    offset: Int,
    length: Int,
    timeout: Duration
  ): IO[Exception, Long] =
    wrap[JLong](
      h =>
        channel.read(
          dsts.map(_.buffer.asInstanceOf[JByteBuffer]).toArray,
          offset,
          length,
          timeout.fold(Long.MaxValue, _.toNanos),
          TimeUnit.NANOSECONDS,
          (),
          h
        )
    ).map(_.toLong)

  final def read[A](
    capacities: List[Int],
    offset: Int,
    length: Int,
    timeout: Duration
  ): IO[Exception, List[Chunk[Byte]]] =
    for {
      bs <- ZIO.collectAll(capacities.map(Buffer.byte(_)))
      l  <- readBuffer(bs, offset, length, timeout)
      as <- ZIO.collectAll(bs.map(_.array))
      r <- if (l == -1) {
            ZIO.fail(new IOException("Connection reset by peer"))
          } else {
            ZIO.succeed(as.map(Chunk.fromArray))
          }
    } yield r

}

object AsynchronousSocketChannel {

  def apply(): ZIO[ZEnv, Exception, AsynchronousSocketChannel] = {
    ZIO.attempt(JAsynchronousSocketChannel.open())
      .refineToOrDie[Exception]
      .map(new AsynchronousSocketChannel(_))
  }

   def apply( channelGroup: AsynchronousChannelGroup ): ZIO[ZEnv, Exception, AsynchronousSocketChannel] = {
    ZIO.attempt(JAsynchronousSocketChannel.open( channelGroup.channelGroup ))
      .refineToOrDie[Exception]
      .map(new AsynchronousSocketChannel(_))
  }

  def apply(asyncSocketChannel: JAsynchronousSocketChannel): AsynchronousSocketChannel =
    new AsynchronousSocketChannel(asyncSocketChannel)
}

object AsynchronousChannel {

  private[nio] def wrap[T](op: JCompletionHandler[T, Unit] => Unit): ZIO[Any, Exception, T] =
    ZIO.async[Any, Exception, T] { k =>
      val handler = new JCompletionHandler[T, Unit] {
        def completed(result: T, u: Unit): Unit =
          k(ZIO.succeed(result))

        def failed(t: Throwable, u: Unit): Unit =
          t match {
            case e: Exception => k(ZIO.fail(e))
            case _            => k(ZIO.die(t))
          }
      }

      try {
        op(handler)
      } catch {
        case e: Exception => k(ZIO.fail(e))
        case t: Throwable => k(ZIO.die(t))
      }
    }

}
