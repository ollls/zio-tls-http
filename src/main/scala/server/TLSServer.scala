package zhttp

import zio.{IO, ZIO}

import zhttp.netio._
import javax.net.ssl.SSLContext
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import zio.ExitCode
import zio.ZIO.attemptBlocking
import scala.concurrent.ExecutionContext
import zio.Chunk

import java.util.concurrent.ExecutorService

class TLSServer[Env](
    port: Int,
    keepAlive: Int = 2000,
    serverIP: String = "0.0.0.0",
    keystore: String = "keystore.jks",
    keyStorePwd: String = "password",
    tlsVersion: String = "TLS"
) {

  val KEYSTORE_PATH       = keystore
  val KEYSTORE_PASSWORD   = keyStorePwd
  val TLS_PROTO           = tlsVersion // default TLSv1.2 in JDK8
  val BINDING_SERVER_IP   = serverIP   // make sure certificate has that IP on SAN's list
  val KEEP_ALIVE          = keepAlive  // ms, good if short for testing with broken site's snaphosts with 404 pages
  val SERVER_PORT         = port
  private var f_terminate = false
  def terminate           = f_terminate = true
  def isTerminated        = f_terminate

  def hostName(address: java.net.SocketAddress) = {
    val ia = address.asInstanceOf[java.net.InetSocketAddress]
    ia.getHostString()
  }

  def myAppLogic(
      processor: IOChannel => zio.Chunk[Byte] => zio.ZIO[Env, Throwable, Unit],
      sslctx: SSLContext = null
  ): ZIO[Env, Throwable, ExitCode] = {

    val cores = Runtime.getRuntime().availableProcessors()
    for {
      ex <- ZIO.executor.map(_.asExecutionContextExecutorService)

      _ <- ZIO.logInfo(s"TLS HTTPS Service started on " + cores + " core CPU")
      _ <- ZIO.logInfo("Listens TLS: " + BINDING_SERVER_IP + ":" + SERVER_PORT + ", keep alive: " + KEEP_ALIVE + " ms ")

      sslCtx <-
        if (sslctx == null) buildSSLContext(TLS_PROTO, KEYSTORE_PATH, KEYSTORE_PASSWORD)
        else ZIO.succeed(sslctx)

      addr <- ZIO.attempt(new java.net.InetSocketAddress(BINDING_SERVER_IP, SERVER_PORT))

      group <- ZIO.attempt(
        java.nio.channels.AsynchronousChannelGroup.withThreadPool(ex)
      )

      server_ch <- ZIO.attempt(
        group.provider().openAsynchronousServerSocketChannel(group).bind(addr)
      )

      accept = ZIO.logInfo("Wait on accept") *> TCPChannel
        .accept(server_ch)
        .tap(c => ZIO.logInfo(s"Connect from remote peer: ${hostName(c.ch.getRemoteAddress())}"))
        .tap(c => ZIO.succeed(c.timeOutMs(KEEP_ALIVE)))
        .flatMap(ch => (ZIO.attempt(TLSChannel(sslCtx, ch)).flatMap(c => c.ssl_init().map((c, _)))))
        .tap(c => ZIO.succeed(c._1.timeOutMs(KEEP_ALIVE)))

      _ <- accept
        .flatMap(ch =>
          ZIO.scoped {
            ZIO.acquireReleaseWith(ZIO.succeed(ch))(_._1.close().catchAll(e => ZIO.unit))(ch => processor(ch._1)(ch._2))
          }.fork
        )
        .catchAll(e => ZIO.logError(e.toString()))
        .repeatUntil(_ => isTerminated)

    } yield ((ExitCode(0)))
  }

  //////////////////////////////////////////////////
  def run(appRoutes: HttpRoutes[Env]*) = {
    val rtr = new HttpRouter[Env](appRoutes.toList)

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

  def buildSSLContext(protocol: String, JKSkeystore: String, password: String): ZIO[Any, Exception, SSLContext] = {

    // resource close - TODO

    val test = attemptBlocking {

      val sslContext: SSLContext = SSLContext.getInstance(protocol)

      val keyStore: KeyStore = KeyStore.getInstance("JKS")

      val ks = new java.io.FileInputStream(JKSkeystore)

      if (ks == null) ZIO.fail(new java.io.FileNotFoundException(JKSkeystore + " keystore file not found."))

      keyStore.load(ks, password.toCharArray())

      val tmf: TrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(keyStore)

      val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      kmf.init(keyStore, password.toCharArray())
      sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
      sslContext
    }

    test.refineToOrDie[Exception]

  }

  def stop =
    for {
      _ <- ZIO.succeed(terminate)
      // kick it one last time
      c <- clients.HttpConnection
        .connect(s"https://$serverIP:$SERVER_PORT", null, tlsBlindTrust = false, s"$KEYSTORE_PATH", s"$KEYSTORE_PASSWORD")
      response <- c.send(clients.ClientRequest(zhttp.Method.GET, "/"))

    } yield ()

}
