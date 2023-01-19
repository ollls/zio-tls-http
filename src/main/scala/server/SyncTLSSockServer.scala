package zhttp

import zio.{IO, ZIO, Task, Chunk}
import zio.ExitCode
import zhttp.netio._
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLSocket
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import java.net.InetSocketAddress
import scala.jdk.CollectionConverters.ListHasAsScala

class SyncTLSSocketServer[Env](
    port: Int,
    keepAlive: Int = 2000,
    serverIP: String = "0.0.0.0",
    keystore: String = "keystore.jks",
    keyStorePwd: String = "password",
    tlsVersion: String = "TLS"
) {
  val KEYSTORE_PATH     = keystore
  val KEYSTORE_PASSWORD = keyStorePwd
  val TLS_PROTO         = tlsVersion // default TLSv1.2 in JDK8
  val BINDING_SERVER_IP = serverIP   // make sure certificate has that IP on SAN's list
  val KEEP_ALIVE        = keepAlive  // ms, good if short for testing with broken site's snaphosts with 404 pages
  val SERVER_PORT       = port

  private var f_terminate = false
  def terminate           = f_terminate = true
  def isTerminated        = f_terminate

  def buildSSLContext(
      protocol: String,
      JKSkeystore: String,
      password: String
  ): Task[SSLContext] = {

    val ctx = ZIO.attemptBlocking {
      val sslContext: SSLContext = SSLContext.getInstance(protocol)
      val keyStore: KeyStore     = KeyStore.getInstance("JKS")
      val ks                     = new java.io.FileInputStream(JKSkeystore)
      if (ks == null)
        ZIO.fail(
          new java.io.FileNotFoundException(
            JKSkeystore + " keystore file not found."
          )
        )
      keyStore.load(ks, password.toCharArray())
      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance(
        TrustManagerFactory.getDefaultAlgorithm()
      )
      tmf.init(keyStore)
      val kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      kmf.init(keyStore, password.toCharArray())
      sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
      sslContext
    }
    ctx
  }

  def hostName(address: java.net.SocketAddress) = {
    val ia = address.asInstanceOf[java.net.InetSocketAddress]
    ia.getHostString()
  }

  def ctrlC_handlerZIO(s0: SSLServerSocket) = ZIO.attempt(
    java.lang.Runtime
      .getRuntime()
      .addShutdownHook(new Thread {
        override def run = {
          println(" abort")
          s0.close()
        }
      })
  )

  def myAppLogic(
      processor: IOChannel => zio.Chunk[Byte] => zio.ZIO[Env, Throwable, Unit],
      sslctx: SSLContext = null
  ): ZIO[Env, Throwable, ExitCode] = {
    val cores = Runtime.getRuntime().availableProcessors()
    for {

      _ <- ZIO.logInfo(s"Java Socket TLS HTTPS started on " + cores + " core CPU")
      _ <- ZIO.logInfo(s"Listens TLS: " + BINDING_SERVER_IP + ":" + SERVER_PORT + ", keep alive: " + KEEP_ALIVE + " ms ")

      sslCtx <-
        if (sslctx == null) buildSSLContext(TLS_PROTO, KEYSTORE_PATH, KEYSTORE_PASSWORD)
        else ZIO.succeed(sslctx)

      addr <- ZIO.attempt(new java.net.InetSocketAddress(BINDING_SERVER_IP, SERVER_PORT))
      server_ch <- ZIO.attempt(
        sslCtx
          .getServerSocketFactory()
          .createServerSocket(SERVER_PORT, 0, addr.getAddress())
          .asInstanceOf[SSLServerSocket]
      )

      accept = ctrlC_handlerZIO(server_ch) *> ZIO
        .attemptBlocking(server_ch.accept().asInstanceOf[SSLSocket])
        .tap(c =>
          ZIO.attempt {
            c.setUseClientMode(false)
            c.setHandshakeApplicationProtocolSelector((eng, list) => {
              if (list.asScala.find(_ == "http/1.1").isDefined) "http/1.1"
              else null
            })
          }
        )
        .tap(c => ZIO.logInfo("Connected: " + hostName(c.getRemoteSocketAddress())))
        .flatMap(c => ZIO.attempt(new SocketChannel(c)))

      _ <- accept
        .flatMap(ch => ZIO.scoped { ZIO.acquireReleaseWith(ZIO.succeed(ch))(_.close().catchAll(e => ZIO.unit))(ch => processor(ch)(Chunk.empty[Byte])) }.fork)
        .catchAll(e => ZIO.logInfo(e.toString()))
        .repeatUntil(_ => isTerminated)

    } yield (ExitCode(0))
  }

  //////////////////////////////////////////////////
  def run(appRoutes: HttpRoutes[Env]*) = {
    val rtr = new HttpRouter(appRoutes.toList)

    val T = myAppLogic(rtr.route).fold(
      e => {
        e.printStackTrace(); zio.ExitCode(1)
      },
      _ => zio.ExitCode(0)
    )
    T
  }

  @deprecated("Use run() with list HttRoutes directly")
  def run(proc: IOChannel => Chunk[Byte] => ZIO[Env, Throwable, Unit]) = {

    val T = myAppLogic(proc).fold(
      e => {
        e.printStackTrace(); zio.ExitCode(1)
      },
      _ => zio.ExitCode(0)
    )

    T
  }

  def stop =
    for {
      _ <- ZIO.succeed(terminate)
      // kick it one last time
      c <- clients.HttpConnection
        .connect(s"http://$serverIP:$SERVER_PORT")
      response <- c.send(clients.ClientRequest(zhttp.Method.GET, "/"))
    } yield ()

}
