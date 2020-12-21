package nio.channels

import javax.net.ssl.SSLContext
import nio.SocketAddress

import zio.IO
import nio.Buffer

import javax.net.ssl.{ SSLEngineResult, SSLSession }
import javax.net.ssl.SSLEngineResult.HandshakeStatus._

import zio.duration.Duration
import zio.ZManaged
import zio.ZEnv

import zio.{ Chunk, IO, ZIO }

import nio.SSLEngine

sealed class TLSChannelError(msg: String) extends Exception(msg)

object AsynchronousTlsByteChannel {

  //no zmanaged on the client
  def apply(
    raw_ch: AsynchronousSocketChannel,
    sslContext: SSLContext
  ): ZIO[ZEnv, Exception, AsynchronousTlsByteChannel] = {
    val open = for {
      ssl_engine <- IO.effect(new SSLEngine(sslContext.createSSLEngine()))
      _          <- ssl_engine.setUseClientMode(true)
      _          <- ssl_engine.setNeedClientAuth(false)
      _          <- AsynchronousTlsByteChannel.open(raw_ch, ssl_engine)
      r          <- IO.effect(new AsynchronousTlsByteChannel(raw_ch, ssl_engine))
    } yield (r)

    open.refineToOrDie[Exception]

  }

  private[nio] def open(raw_ch: AsynchronousByteChannel, ssl_engine: SSLEngine) = {
    val BUFF_SZ = ssl_engine.engine.getSession().getPacketBufferSize()

    val result = for {
      sequential_unwrap_flag <- zio.Ref.make(false)
      in_buf                 <- Buffer.byte(BUFF_SZ)
      out_buf                <- Buffer.byte(BUFF_SZ)
      empty                  <- Buffer.byte(0)
      _                      <- ssl_engine.wrap(empty, out_buf) *> out_buf.flip *> raw_ch.writeBuffer(out_buf)
      _                      <- out_buf.clear
      loop = ssl_engine.getHandshakeStatus().flatMap {
        _ match {
          case NEED_WRAP =>
            for {
              //data to check in_buff to prevent unnecessary read, and let to process the rest wih sequential unwrap
              pos_            <- in_buf.position
              lim_            <- in_buf.limit
              
              _               <- out_buf.clear
              result          <- ssl_engine.wrap(empty, out_buf)
              _               <- out_buf.flip
              //prevent reset to read if buffer has more data, now we can realy on underflow processing later
              _               <- if ( pos_ > 0 && pos_ < lim_ ) IO.unit
                                 else sequential_unwrap_flag.set(false)
              
        
              handshakeStatus <- raw_ch.writeBuffer(out_buf) *> IO.effect(result.getHandshakeStatus)
            } yield (handshakeStatus)

          case NEED_UNWRAP => {
            sequential_unwrap_flag.get.flatMap(
              _sequential_unwrap_flag =>
                if (_sequential_unwrap_flag == false)
                  for {
                    _      <- in_buf.clear
                    _      <- out_buf.clear
                    n      <- raw_ch.readBuffer(in_buf)
                    _     <- if ( n == -1 ) ZIO.fail( new TLSChannelError( "AsynchronousTlsByteChannel: no data to unwrap") )
                              else ZIO.unit
                    _      <- in_buf.flip
                    _      <- sequential_unwrap_flag.set(true)
                    result <- ssl_engine.unwrap(in_buf, out_buf)
                  } yield (result.getHandshakeStatus())
                else
                  for {
                    _ <- out_buf.clear

                    pos <- in_buf.position
                    lim <- in_buf.limit

                    hStat <- if (pos == lim) {
                              sequential_unwrap_flag.set(false) *> IO.succeed(NEED_UNWRAP)
                            } else {
                              for {
                                r <- ssl_engine.unwrap(in_buf, out_buf)
                                _ <- if (r.getStatus() == javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                                      for {

                                        p1 <- in_buf.position
                                        l1 <- in_buf.limit

                                        //underflow read() append to the end, till BUF_SZ
                                        _ <- in_buf.position(l1)
                                        _ <- in_buf.limit(BUFF_SZ)

                                        _ <- raw_ch.readBuffer(in_buf)

                                        p2 <- in_buf.position //new limit
                                        _  <- in_buf.limit(p2)
                                        _  <- in_buf.position(p1) //back to original position, we had before read

                                        r <- ssl_engine
                                              .unwrap(in_buf, out_buf) //.map( r => { println( "SECOND " + r.toString); r })

                                      } yield (r)

                                    } else IO(r)

                              } yield (r.getHandshakeStatus)
                            }
                  } yield (hStat)
            )
          }

          case NEED_TASK => ssl_engine.getDelegatedTask() *> IO.succeed(NEED_TASK)

          case NOT_HANDSHAKING => IO.succeed(NOT_HANDSHAKING)

          case FINISHED => IO.succeed(FINISHED)

          case _ =>
            IO.fail(new TLSChannelError("unknown: getHandshakeStatus() - possible SSLEngine commpatibility problem"))

        }
      }

      _ <- loop
            .repeat(zio.Schedule.recurWhile(c => { /*println(c.toString);*/ c != FINISHED }))
            .refineToOrDie[Exception]

    } yield ()

    result

  }
}

class AsynchronousTlsByteChannel(private val channel: AsynchronousSocketChannel, private val sslEngine: SSLEngine) {

  val TLS_PACKET_SZ = sslEngine.engine.getSession().getPacketBufferSize()
  val APP_PACKET_SZ = sslEngine.engine.getSession().getApplicationBufferSize()

  //prealoc carryover buffer, position getting saved between calls
  val IN_J_BUFFER = java.nio.ByteBuffer.allocate(TLS_PACKET_SZ * 2)

  //used for Keep-Alive, if HTTPS
  var READ_TIMEOUT_MS: Long = 5000

  final def keepAlive(ms: Long) = { READ_TIMEOUT_MS = ms; this }

  def getSession: IO[Exception, SSLSession] =
    IO.effect(sslEngine.engine.getSession()).refineToOrDie[Exception]

  def remoteAddress: ZIO[ZEnv, Exception, Option[SocketAddress]] = channel.remoteAddress

  def readBuffer(out_b: java.nio.ByteBuffer): ZIO[ZEnv, Exception, Unit] = {

    val out = Buffer.byte(out_b)

    val result = for {

      in <- IO.effectTotal(new nio.ByteBuffer(IN_J_BUFFER)) //reuse carryover buffer from previous read(), buffer was compacted with compact(), only non-processed data left

      nb <- channel.readBuffer(in, Duration(READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))

      _ <- if (nb == -1) IO.fail(new TLSChannelError("AsynchronousServerTlsByteChannel#read() with -1 "))
          else IO.unit

      _ <- in.flip

      loop = for {
        res  <- sslEngine.unwrap(in, out)
        stat <- IO.effect(res.getStatus())
        rem <- if (stat != SSLEngineResult.Status.OK) {
                if (stat == SSLEngineResult.Status.BUFFER_UNDERFLOW || stat == SSLEngineResult.Status.BUFFER_OVERFLOW)
                  IO.succeed(0)
                else
                  IO.fail(new TLSChannelError("AsynchronousTlsByteChannel#read() " + res.toString()))
              } else in.remaining
      } yield (rem)
      _ <- loop.repeat(zio.Schedule.recurWhile(_ != 0))
      _ <- out.flip
      //****compact, some data may be carried over for next read call
      _ <- in.compact

    } yield (out)

    result.unit.refineToOrDie[Exception]

  }

  //////////////////////////////////////////////////////////////////////////
  def read: ZIO[ZEnv, Exception, Chunk[Byte]] = {
    val OUT_BUF_SZ = APP_PACKET_SZ * 2
    val result = for {

      out <- Buffer.byte(OUT_BUF_SZ)
      in  <- IO.effectTotal(new nio.ByteBuffer(IN_J_BUFFER)) //reuse carryover buffer from previous read(), buffer was compacted with compact(), only non-processed data left

      //_   <- zio.console.putStrLn( "before read " + expected_size + " " + OUT_BUF_SZ )

      nb <- channel.readBuffer(in, Duration(READ_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS))

      _ <- if (nb == -1) IO.fail(new TLSChannelError("AsynchronousServerTlsByteChannel#read() with -1 "))
          else IO.unit

      _ <- in.flip

      loop = for {
        res  <- sslEngine.unwrap(in, out)
        stat <- IO.effect(res.getStatus())
        //_    <- zio.console.putStrLn( "read " + stat.toString() )
        rem <- if (stat != SSLEngineResult.Status.OK) {
                if (stat == SSLEngineResult.Status.BUFFER_UNDERFLOW || stat == SSLEngineResult.Status.BUFFER_OVERFLOW)
                  IO.succeed(0)
                else
                  IO.fail(new TLSChannelError("AsynchronousTlsByteChannel#read() " + res.toString()))
              } else in.remaining
      } yield (rem)
      _ <- loop.repeat(zio.Schedule.recurWhile(_ != 0))
      _ <- out.flip
      //****compact, some data may be carried over for next read call
      _ <- in.compact

      limit <- out.limit
      array <- out.array

      chunk <- IO.effect(Chunk.fromArray(array).take(limit))

    } yield (chunk)

    result.refineToOrDie[Exception]
  }

  final def write(chunk: Chunk[Byte]): ZIO[ZEnv, Exception, Int] = {
    val res = for {

      in <- Buffer.byte(chunk)

      out <- Buffer.byte(if (chunk.length > TLS_PACKET_SZ) chunk.length * 2 else TLS_PACKET_SZ * 2)
      _   <- out.clear

      loop = for {
        res  <- sslEngine.wrap(in, out)
        stat <- IO.effect(res.getStatus())
        _ <- if (stat != SSLEngineResult.Status.OK)
              IO.fail(new TLSChannelError("AsynchronousTlsByteChannel#write() " + res.toString()))
            else IO.unit
        rem <- in.remaining
      } yield (rem)
      _ <- loop.repeat(zio.Schedule.recurWhile(_ != 0))
      _ <- out.flip

      nBytes <- channel.writeBuffer(out)

    } yield (nBytes)

    res.refineToOrDie[Exception]
  }

  //just a socket close on already closed/broken connection
  final def close_socket_only: IO[Nothing, Unit] =
    channel.close.catchAll(_ => IO.unit)

  //close with TLS close_notify
  final def close: ZIO[ZEnv, Nothing, Unit] = {
    //println( "CLOSE <->")
    val result = for {
      _     <- IO.effect(sslEngine.engine.getSession().invalidate())
      _     <- sslEngine.closeOutbound()
      empty <- Buffer.byte(0)
      out   <- Buffer.byte(TLS_PACKET_SZ)
      loop  = sslEngine.wrap(empty, out) *> sslEngine.isOutboundDone()
      _     <- loop.repeat(zio.Schedule.recurUntil((status: Boolean) => status))
      _     <- out.flip
      _     <- channel.writeBuffer(out)
      _     <- channel.close
    } yield ()

    result.catchAll { _ =>
      /*println( e.printStackTrace );*/
      IO.unit
    }
  }

}

object AsynchronousServerTlsByteChannel {

  def apply(
    raw_ch: AsynchronousSocketChannel,
    sslContext: SSLContext
  ): ZManaged[ZEnv, Exception, AsynchronousTlsByteChannel] = {

    val open = for {
      ssl_engine <- IO.effect(new SSLEngine(sslContext.createSSLEngine()))
      _          <- ssl_engine.setUseClientMode(false)
      _          <- AsynchronousServerTlsByteChannel.open(raw_ch, ssl_engine)
      r          <- IO.effect(new AsynchronousTlsByteChannel(raw_ch, ssl_engine))
    } yield (r)

    ZManaged.make(open.refineToOrDie[Exception])(_.close)

  }

  //TLS handshake is here
  private[nio] def open(raw_ch: AsynchronousByteChannel, ssl_engine: SSLEngine) = {
    val BUFF_SZ = ssl_engine.engine.getSession().getPacketBufferSize()

    val result = for {

      sequential_unwrap_flag <- zio.Ref.make(false)

      in_buf  <- Buffer.byte(BUFF_SZ)
      out_buf <- Buffer.byte(BUFF_SZ)
      empty   <- Buffer.byte(0)
      _       <- raw_ch.readBuffer(in_buf) *> in_buf.flip *> ssl_engine.unwrap(in_buf, out_buf)
      loop = ssl_engine.getHandshakeStatus().flatMap {
        _ match {

          case NEED_WRAP =>
            for {
              _               <- out_buf.clear
              result          <- ssl_engine.wrap(empty, out_buf)
              _               <- out_buf.flip
              _               <- sequential_unwrap_flag.set(false)
              handshakeStatus <- raw_ch.writeBuffer(out_buf) *> IO.effect(result.getHandshakeStatus)
            } yield (handshakeStatus)

          case NEED_UNWRAP => {
            sequential_unwrap_flag.get.flatMap(
              _sequential_unwrap_flag =>
                if (_sequential_unwrap_flag == false)
                  for {
                    _      <- in_buf.clear
                    _      <- out_buf.clear
                    _      <- raw_ch.readBuffer(in_buf)
                    _      <- in_buf.flip
                    _      <- sequential_unwrap_flag.set(true)
                    result <- ssl_engine.unwrap(in_buf, out_buf)
                  } yield (result.getHandshakeStatus())
                else
                  for {
                    _ <- out_buf.clear

                    pos <- in_buf.position
                    lim <- in_buf.limit

                    hStat <- if (pos == lim)
                              sequential_unwrap_flag.set(false) *> IO.succeed(NEED_UNWRAP)
                            else
                              ssl_engine.unwrap(in_buf, out_buf).map(_.getHandshakeStatus())
                  } yield (hStat)
            )
          }

          case NEED_TASK => ssl_engine.getDelegatedTask() *> IO.succeed(NEED_TASK)

          case NOT_HANDSHAKING => IO.succeed(NOT_HANDSHAKING)

          case FINISHED => IO.succeed(FINISHED)

          case _ =>
            IO.fail(new TLSChannelError("unknown: getHandshakeStatus() - possible SSLEngine commpatibility problem"))

        }
      }

      r <- loop
            .repeat(zio.Schedule.recurWhile(c => { c != FINISHED }))
            .refineToOrDie[Exception]

    } yield (r)
    result
  }

}
