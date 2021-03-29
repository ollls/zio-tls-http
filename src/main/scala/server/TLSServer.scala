package zhttp

import zio.{ IO, ZEnv, ZIO }
import nio.SocketAddress

import zio.blocking._

import nio._
import nio.channels._
import javax.net.ssl.SSLContext
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import zio.ExitCode
import zio.Has

////{ Executors, ExecutorService, ThreadPoolExecutor }

class TLSServer[MyEnv <: Has[MyLogging.Service]](
  port: Int,
  keepAlive: Int = 2000,
  serverIP: String = "0.0.0.0",
  keystore: String = "keystore.jks",
  keyStorePwd: String = "password",
  tlsVersion: String = "TLS"
) {

  val KEYSTORE_PATH     = keystore
  val KEYSTORE_PASSWORD = keyStorePwd
  val TLS_PROTO         = tlsVersion //default TLSv1.2 in JDK8
  val BINDING_SERVER_IP = serverIP //make sure certificate has that IP on SAN's list
  val KEEP_ALIVE        = keepAlive //ms, good if short for testing with broken site's snaphosts with 404 pages
  val SERVER_PORT       = port

  private var processor: Channel => ZIO[ZEnv with MyEnv, Exception, Unit] = null

  private var f_terminate = false
  def terminate           = f_terminate = true
  def isTerminated        = f_terminate

  /////////////////////////////////
  def myAppLogic(sslctx: SSLContext = null): ZIO[ZEnv with MyEnv, Throwable, ExitCode] =
    for {

      metr <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.platform.executor.metrics)

      _ <- MyLogging.info(
            "console",
            s"TLS HTTP Service started on " + SERVER_PORT + ", ZIO concurrency lvl: " + metr.get.concurrency + " threads"
          )
      _ <- MyLogging.info(
            "console",
            "Listens: " + BINDING_SERVER_IP + ":" + SERVER_PORT + ", keep alive: " + KEEP_ALIVE + " ms"
          )

      executor <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.platform.executor.asECES)

      ssl_context <- if (sslctx == null) buildSSLContext(TLS_PROTO, KEYSTORE_PATH, KEYSTORE_PASSWORD)
                    else ZIO.succeed(sslctx)

      address <- SocketAddress.inetSocketAddress(BINDING_SERVER_IP, SERVER_PORT)

      group <- AsynchronousChannelGroup(executor)

      _ <- group.openAsynchronousServerSocketChannel().use { srv =>
            {
              for {

                _ <- srv.bind(address)

                loop = srv.accept2
                  .flatMap(
                    channel =>
                      channel.remoteAddress.flatMap(
                        c =>
                          MyLogging
                            .debug("console", "Connected: " + c.get.toInetSocketAddress.address.canonicalHostName)
                      ) *>
                        AsynchronousServerTlsByteChannel(channel, ssl_context)
                          .use(c => processor(new TlsChannel(c.keepAlive(KEEP_ALIVE))))
                          .catchAll( e => {
                            MyLogging.error("console", e.toString ) *>
                            IO.succeed(0)
                          })
                          .fork
                  )
                  .catchAll(_ => IO.succeed(0))

                _ <- loop.repeatUntil(_ => isTerminated)

              } yield ()
            }
          }

    } yield (ExitCode(0))

  //////////////////////////////////////////////////
  def runWithSSLContext(proc: Channel => ZIO[ZEnv with MyEnv, Exception, Unit], sslContext: SSLContext) = {

    processor = proc

    val T = myAppLogic(sslContext).fold(e => {
      e.printStackTrace(); zio.ExitCode(1)
    }, _ => zio.ExitCode(0))

    T
  }

  //////////////////////////////////////////////////
  def run(proc: Channel => ZIO[ZEnv with MyEnv, Exception, Unit]) = {

    processor = proc

    val T = myAppLogic().fold(e => {
      e.printStackTrace(); zio.ExitCode(1)
    }, _ => zio.ExitCode(0))

    T
  }

  def buildSSLContext(protocol: String, JKSkeystore: String, password: String): ZIO[Blocking, Exception, SSLContext] = {

    //resource close - TODO

    val test = effectBlocking {

      val sslContext: SSLContext = SSLContext.getInstance(protocol)

      val keyStore: KeyStore = KeyStore.getInstance("JKS")

      val ks = new java.io.FileInputStream(JKSkeystore)

      if (ks == null) IO.fail(new java.io.FileNotFoundException(JKSkeystore + " keystore file not found."))

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
      _ <- ZIO.effectTotal(terminate)
      //kick it one last time
      c <- clients.HttpConnection
            .connect(s"https://localhost:$SERVER_PORT", null, tlsBlindTrust = false, s"$KEYSTORE_PATH", s"$KEYSTORE_PASSWORD")
      response <- c.send(clients.ClientRequest(zhttp.Method.GET, "/"))

      svc <- MyLogging.logService
      _   <- svc.shutdown

    } yield ()

}
