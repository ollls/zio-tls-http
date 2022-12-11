package zhttp.netio

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.HandshakeStatus._
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.KeyStore

import javax.net.ssl.{SSLEngineResult, SSLSession}

import zio.Ref

import java.util.concurrent.TimeUnit
import java.nio.channels.Channel
import zio.{ZIO, Task}
import java.nio.ByteBuffer
import zio.Chunk

import java.nio.channels.{AsynchronousChannelGroup, AsynchronousServerSocketChannel, AsynchronousSocketChannel, CompletionHandler}

import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.io.FileInputStream
import java.io.File
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import scala.jdk.CollectionConverters.ListHasAsScala
import java.nio.ByteBuffer

sealed case class TLSChannelError(msg: String) extends Exception(msg)

object TLSChannel {

  val READ_HANDSHAKE_TIMEOUT_MS = 5000
  val TLS_PROTOCOL_TAG          = "TLSv1.2"

  private def loadDefaultKeyStore(): KeyStore = {
    val relativeCacertsPath = "/lib/security/cacerts".replace("/", File.separator);
    val filename            = System.getProperty("java.home") + relativeCacertsPath;
    val is                  = new FileInputStream(filename);

    val keystore = KeyStore.getInstance(KeyStore.getDefaultType());
    val password = "changeit";
    keystore.load(is, password.toCharArray());

    keystore;
  }

  def buildSSLContext(protocol: String, JKSkeystore: String, password: String) = {
    // JKSkeystore == null, only if blind trust was requested

    val sslContext: SSLContext = SSLContext.getInstance(protocol)

    val keyStore = if (JKSkeystore == null) {
      loadDefaultKeyStore()
    } else {
      val keyStore: KeyStore = KeyStore.getInstance("JKS")
      val ks                 = new java.io.FileInputStream(JKSkeystore)
      keyStore.load(ks, password.toCharArray())
      keyStore
    }

    val trustMgrs = if (JKSkeystore == null) {
      Array[TrustManager](new X509TrustManager() {
        def getAcceptedIssuers(): Array[X509Certificate]                   = null
        def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
        def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      })

    } else {
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(keyStore)
      tmf.getTrustManagers()
    }

    val pwd = if (JKSkeystore == null) "changeit" else password

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, pwd.toCharArray())
    sslContext.init(kmf.getKeyManagers(), trustMgrs, null);

    sslContext
  }

  def connect(
      host: String,
      port: Int,
      group: AsynchronousChannelGroup = null,
      blindTrust: Boolean = false,
      trustKeystore: String = null,
      password: String = ""
  ): Task[TLSChannel] = {
    val T = for {
      ssl_ctx <-
        if (trustKeystore == null && blindTrust == false)
          ZIO.attemptBlocking(SSLContext.getDefault())
        else ZIO.attemptBlocking(buildSSLContext(TLS_PROTOCOL_TAG, trustKeystore, password))
      tcp_c <- TCPChannel.connect(host, port, group)
      ch    <- ZIO.attempt(new TLSChannel(ssl_ctx, tcp_c))
      _     <- ch.ssl_initClient()
    } yield (ch)
    T
  }
}

class TLSChannel(val ctx: SSLContext, rch: TCPChannel) extends IOChannel {

  var f_SSL: SSLEngine = new SSLEngine(ctx.createSSLEngine())
  val TLS_PACKET_SZ    = f_SSL.engine.getSession().getPacketBufferSize()
  val APP_PACKET_SZ    = f_SSL.engine.getSession().getApplicationBufferSize()

  // how many packets we can consume per read() call N of TLS_PACKET_SZ  -> N of APP_PACKET_SZ
  val MULTIPLER = 4

  // prealoc carryover buffer, position getting saved between calls
  private[this] val IN_J_BUFFER = java.nio.ByteBuffer.allocate(TLS_PACKET_SZ * MULTIPLER)

  private[this] def doHandshakeClient() = {
    // val BUFF_SZ = ssl_engine.engine.getSession().getPacketBufferSize()

    var loop_cntr = 0 // to avoid issues with non-SSL sockets sending junk data

    val result = for {
      sequential_unwrap_flag <- Ref.make(false)

      in_buf  <- ZIO.attempt(ByteBuffer.allocate(TLS_PACKET_SZ))
      out_buf <- ZIO.attempt(ByteBuffer.allocate(TLS_PACKET_SZ))
      empty   <- ZIO.attempt(ByteBuffer.allocate(0))

      _ <- f_SSL.wrap(empty, out_buf) *> ZIO.attempt(out_buf.flip) *> rch.write(out_buf)
      _ <- ZIO.attempt(out_buf.clear)
      loop = f_SSL.getHandshakeStatus().flatMap {
        _ match {
          case NEED_WRAP =>
            for {
              // data to check in_buff to prevent unnecessary read, and let to process the rest wih sequential unwrap
              pos_ <- ZIO.attempt(in_buf.position())
              lim_ <- ZIO.attempt(in_buf.limit())

              _      <- ZIO.attempt(out_buf.clear())
              result <- f_SSL.wrap(empty, out_buf)
              _      <- ZIO.attempt(out_buf.flip)
              // prevent reset to read if buffer has more data, now we can realy on underflow processing later
              _ <-
                if (pos_ > 0 && pos_ < lim_) ZIO.unit
                else sequential_unwrap_flag.set(false)

              handshakeStatus <- rch.write(out_buf) *> ZIO.attempt(result.getHandshakeStatus)
            } yield (handshakeStatus)

          case NEED_UNWRAP => {
            sequential_unwrap_flag.get.flatMap(_sequential_unwrap_flag =>
              if (_sequential_unwrap_flag == false)
                for {
                  _ <- ZIO.attempt(in_buf.clear)
                  _ <- ZIO.attempt(out_buf.clear)
                  nb <- rch
                    .readBuffer(in_buf, TLSChannel.READ_HANDSHAKE_TIMEOUT_MS)
                    .mapError(e => new TLSChannelError("TLS Handshake error timeout: " + e.toString))

                  _ <- if (nb == -1) ZIO.fail(new TLSChannelError("TLS Handshake, broken pipe")) else ZIO.unit

                  _      <- ZIO.attempt(in_buf.flip)
                  _      <- sequential_unwrap_flag.set(true)
                  result <- f_SSL.unwrap(in_buf, out_buf)
                } yield (result.getHandshakeStatus())
              else
                for {
                  _ <- ZIO.attempt(out_buf.clear)

                  pos <- ZIO.attempt(in_buf.position())
                  lim <- ZIO.attempt(in_buf.limit())

                  hStat <-
                    if (pos == lim) {
                      sequential_unwrap_flag.set(false) *> ZIO.succeed(NEED_UNWRAP)
                    } else {
                      for {
                        r <- f_SSL.unwrap(in_buf, out_buf)
                        _ <-
                          if (r.getStatus() == javax.net.ssl.SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            for {

                              p1 <- ZIO.attempt(in_buf.position())
                              l1 <- ZIO.attempt(in_buf.limit())

                              // underflow read() append to the end, till BUF_SZ
                              _ <- ZIO.attempt(in_buf.position(l1))
                              _ <- ZIO.attempt(in_buf.limit(TLS_PACKET_SZ))

                              nb <- rch
                                .readBuffer(
                                  in_buf,
                                  TLSChannel.READ_HANDSHAKE_TIMEOUT_MS
                                )
                                .mapError(e => new TLSChannelError("TLS Handshake error timeout: " + e.toString))

                              _ <-
                                if (nb == -1) ZIO.fail(new TLSChannelError("TLS Handshake, broken pipe"))
                                else ZIO.unit

                              p2 <- ZIO.attempt(in_buf.position())   // new limit
                              _  <- ZIO.attempt(in_buf.limit(p2))
                              _  <- ZIO.attempt(in_buf.position(p1)) // back to original position, we had before read

                              r <- f_SSL
                                .unwrap(in_buf, out_buf) // .map( r => { println( "SECOND " + r.toString); r })

                            } yield (r)

                          } else ZIO.succeed(r)

                      } yield (r.getHandshakeStatus)
                    }
                } yield (hStat)
            )
          }

          case NEED_TASK => f_SSL.getDelegatedTask() *> ZIO.succeed(NEED_TASK)

          case NOT_HANDSHAKING => ZIO.succeed(NOT_HANDSHAKING)

          case FINISHED => ZIO.succeed(FINISHED)

          case _ =>
            ZIO.fail(
              new TLSChannelError("unknown: getHandshakeStatus() - possible SSLEngine commpatibility problem")
            )
        }
      }
      r <- loop
        .repeatWhile(c => {
          loop_cntr = loop_cntr + 1; /*println( c.toString + " *** " + loop_cntr );*/
          c != FINISHED && loop_cntr < 300
        })

    } yield (r)
    result
  }

  private[this] def doHandshake(): Task[(HandshakeStatus, Chunk[Byte])] = {
    var loop_cntr = 0 // to avoid issues with non-SSL sockets sending junk data
    var temp      = 0
    val result = for {
      sequential_unwrap_flag <- Ref.make(false)

      in_buf  <- ZIO.attempt(ByteBuffer.allocate(TLS_PACKET_SZ))
      out_buf <- ZIO.attempt(ByteBuffer.allocate(TLS_PACKET_SZ))
      empty   <- ZIO.attempt(ByteBuffer.allocate(0))

      nbw <- rch
        .readBuffer(in_buf, TLSChannel.READ_HANDSHAKE_TIMEOUT_MS)
        .mapError(e => new TLSChannelError("TLS Handshake error timeout: " + e.toString))

      _ <- if (nbw == -1) ZIO.fail(new TLSChannelError("TLS Handshake, broken pipe")) else ZIO.unit
      _ <- ZIO.attempt(in_buf.flip)
      _ <- f_SSL.unwrap(in_buf, out_buf)
      loop = f_SSL.getHandshakeStatus().flatMap {
        _ match {

          case NEED_WRAP =>
            for {
              _               <- ZIO.attempt(out_buf.clear)
              result          <- f_SSL.wrap(empty, out_buf)
              _               <- ZIO.attempt(out_buf.flip)
              _               <- sequential_unwrap_flag.set(false)
              handshakeStatus <- rch.write(out_buf) *> ZIO.succeed(result.getHandshakeStatus)
            } yield (handshakeStatus)

          case NEED_UNWRAP => {
            sequential_unwrap_flag.get.flatMap(_sequential_unwrap_flag =>
              if (_sequential_unwrap_flag == false)
                for {
                  _ <- ZIO.attempt(in_buf.clear)
                  _ <- ZIO.attempt(out_buf.clear)
                  nbr <- rch
                    .readBuffer(in_buf, TLSChannel.READ_HANDSHAKE_TIMEOUT_MS)
                    .mapError(e => new TLSChannelError("TLS Handshake error timeout: " + e.toString))
                  _      <- if (nbr == -1) ZIO.fail(new TLSChannelError("TLS Handshake, broken pipe")) else ZIO.unit
                  _      <- ZIO.succeed { temp = nbr }
                  _      <- ZIO.attempt(in_buf.flip)
                  _      <- sequential_unwrap_flag.set(true)
                  result <- f_SSL.unwrap(in_buf, out_buf)
                } yield (result.getHandshakeStatus())
              else
                for {
                  _ <- ZIO.attempt(out_buf.clear)

                  pos <- ZIO.attempt(in_buf.position())
                  lim <- ZIO.attempt(in_buf.limit())

                  hStat <-
                    if (pos == lim)
                      sequential_unwrap_flag.set(false) *> ZIO.succeed(NEED_UNWRAP)
                    else
                      f_SSL.unwrap(in_buf, out_buf).map(_.getHandshakeStatus())
                } yield (hStat)
            )
          }

          case NEED_TASK => f_SSL.getDelegatedTask() *> ZIO.succeed(NEED_TASK)

          case NOT_HANDSHAKING => ZIO.succeed(NOT_HANDSHAKING)

          case FINISHED => ZIO.succeed(FINISHED)

          case _ =>
            ZIO.fail(
              new TLSChannelError("unknown: getHandshakeStatus() - possible SSLEngine commpatibility problem")
            )

        }
      }
      r <- loop
        .repeatWhile(c => { loop_cntr = loop_cntr + 1; c != FINISHED && loop_cntr < 300 })
      // .flatTap(_ =>
      //  IO.println("POS=" + in_buf.position() + "  " + temp + "  P4 = " + TLS_PACKET_SZ + "/" + APP_PACKET_SZ)
      // )
      // SSL data lefover issue.
      // very rare case when TLS handshake reads more then neccssary
      // this happens on very first connection upon restart only one time, when JVM is slow on first load/compilation
      // not really worth the efforts to fix, but always good to cover everything.
      _ <- ZIO.attempt(out_buf.clear())
      _ <- f_SSL.unwrap(in_buf, out_buf).repeatWhile { rs =>
        ((rs.getStatus() == SSLEngineResult.Status.OK) && (in_buf.remaining() != 0))
      }
      pos      <- ZIO.attempt(out_buf.position())
      _        <- ZIO.attempt(out_buf.flip())
      _        <- ZIO.attempt(out_buf.limit(pos))
      leftOver <- ZIO.attempt(Chunk.fromByteBuffer(out_buf))

      _ <- ZIO.attempt(in_buf.limit(temp))
      // uncompleted data block, which cannot be decryted. It will be cached with TCPChanel#put, we will prepend it to next raw read.
      // the other part we managed to decrypt will go as leftOver to be passed to app logic read write.
      _ <- rch.put(in_buf)

    } yield ((r, leftOver))
    result
  }

  // close with TLS close_notify
  final def close(): Task[Unit] = {
    val result = for {
      _     <- ZIO.attempt(f_SSL.engine.getSession().invalidate())
      _     <- f_SSL.closeOutbound()
      empty <- ZIO.attempt(ByteBuffer.allocate(0))
      out   <- ZIO.attempt(ByteBuffer.allocate(TLS_PACKET_SZ))
      loop = f_SSL.wrap(empty, out) *> f_SSL.isOutboundDone()
      _ <- loop.repeatUntil((status: Boolean) => status)
      _ <- ZIO.attempt(out.flip)
      _ <- rch.write(out)
      _ <- rch.close()
    } yield ()

    result.catchAll(_ => ZIO.unit)
  }

  // Client side SSL Init
  // for 99% there will be no leftover but under extreme load or upon JVM init it happens
  def ssl_initClient(): Task[Unit] = {
    for {
      _ <- f_SSL.setUseClientMode(true)
      x <- doHandshakeClient()
      _ <-
        if (x != FINISHED) {
          ZIO.fail(new TLSChannelError("TLS Handshake error, plain text connection?"))
        } else ZIO.unit
    } yield ()

  }

  // Server side SSL Init
  // returns leftover chunk which needs to be used before we read chanel again.
  // for 99% there will be no leftover but under extreme load or upon JVM init it happens
  def ssl_init(): Task[Chunk[Byte]] = {
    for {
      _ <- f_SSL.setUseClientMode(false)

      _ <- ZIO.attempt(f_SSL.engine.setHandshakeApplicationProtocolSelector((eng, list) => {
        if (list.asScala.find(_ == "http/1.1").isDefined) "http/1.1"
        else null
      }))

      x <- doHandshake()
      _ <-
        if (x._1 != FINISHED) {
          ZIO.fail(new TLSChannelError("TLS Handshake error, plain text connection?"))
        } else ZIO.unit
    } yield (x._2)
  }

  // Server side SSL Init with ALPN for H2 only
  // ALPN (Application Layer Protocol Negotiation) for http2
  // returns leftover chunk which needs to be used before we read chanel again.
  // for 99% there will be no leftover but under extreme load or upon JVM init it happens
  def ssl_init_h2(): Task[Chunk[Byte]] = {
    for {
      _ <- f_SSL.setUseClientMode(false)
      _ <- ZIO.attempt(f_SSL.engine.setHandshakeApplicationProtocolSelector((eng, list) => {
        if (list.asScala.find(_ == "h2").isDefined) "h2"
        else null
      }))

      x <- doHandshake()

      _ <- ZIO.fail(new TLSChannelError("TLS Handshake error, plain text connection?")).when((x._1 != FINISHED))

    } yield (x._2)
  }

  ////////////////////////////////////////////////////
  def write(in: ByteBuffer): Task[Int] = {

    val res = for {
      out <- ZIO.attempt(ByteBuffer.allocate(if (in.limit() > TLS_PACKET_SZ) in.limit() * 3 else TLS_PACKET_SZ * 3))
      // _ <- IO(out.clear)

      loop = for {
        res  <- f_SSL.wrap(in, out)
        stat <- ZIO.succeed(res.getStatus())
        _ <-
          if (stat != SSLEngineResult.Status.OK) {
            ZIO.fail(new Exception("AsynchronousTlsByteChannel#write()! " + res.toString()))
          } else ZIO.unit
        rem <- ZIO.attempt(in.remaining())
      } yield (rem)
      _ <- loop.repeatWhile(_ != 0)
      _ <- ZIO.attempt(out.flip)

      nBytes <- rch.write(out)
    } yield (nBytes)
    res
  }

  def write(chunk: Chunk[Byte]): Task[Int] = {
    write(ByteBuffer.wrap(chunk.toArray))
  }

  private[zhttp] def readBuffer(out: ByteBuffer, timeoutMs: Int): Task[Int] = {
    val result = for {
      nb <- rch.readBuffer(IN_J_BUFFER, timeoutMs)
      _ <-
        if (nb == -1) ZIO.fail(new TLSChannelError("AsynchronousServerTlsByteChannel#read() with -1 "))
        else ZIO.unit

      _ <- ZIO.attempt(IN_J_BUFFER.flip)

      loop = for {
        res  <- f_SSL.unwrap(IN_J_BUFFER, out)
        stat <- ZIO.attempt(res.getStatus())
        rem <-
          if (stat != SSLEngineResult.Status.OK) {
            if (stat == SSLEngineResult.Status.BUFFER_UNDERFLOW || stat == SSLEngineResult.Status.BUFFER_OVERFLOW)
              ZIO.succeed(0)
            else
              ZIO.fail(new TLSChannelError("AsynchronousTlsByteChannel#read() " + res.toString()))
          } else ZIO.attempt(IN_J_BUFFER.remaining())
      } yield (rem)
      _        <- loop.repeatWhile(_ != 0)
      save_pos <- ZIO.attempt(out.position())
      _        <- ZIO.attempt(out.flip)
      // ****compact, some data may be carried over for next read call
      _ <- ZIO.attempt(IN_J_BUFFER.compact)
    } yield (save_pos)

    result

  }

  def read(timeoutMs: Int): Task[Chunk[Byte]] = {
    for {
      bb    <- ZIO.attempt(ByteBuffer.allocate(MULTIPLER * APP_PACKET_SZ))
      n     <- readBuffer(bb, timeoutMs)
      chunk <- ZIO.attempt(Chunk.fromByteBuffer(bb))
    } yield (chunk)
  }

  def remoteAddress(): Task[SocketAddress] = ZIO.attempt(rch.ch.getRemoteAddress())
}
