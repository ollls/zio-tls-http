package zhttp

import zio.{ IO, ZIO }
import nio.SocketAddress



import nio._
import nio.channels._
import javax.net.ssl.SSLContext
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import zio.ExitCode
import zio.ZIO.attemptBlocking
import scala.concurrent.ExecutionContext
import zio.Executor
import zio.RuntimeConfig

import java.util.concurrent.ExecutorService

class TLSServer[MyEnv <: MyLogging.Service](
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

  private var processor: Channel => ZIO[ MyEnv, Exception, Unit] = null

  private var f_terminate = false
  def terminate           = f_terminate = true
  def isTerminated        = f_terminate

  /////////////////////////////////
  def myAppLogic( executor : ExecutorService, threadsNum : Int, sslctx: SSLContext = null ): ZIO[ MyEnv, Throwable, ExitCode] =

    for {
      _ <- MyLogging.info(
            "console",
            s"TLS HTTP Service started on " + SERVER_PORT + ", ZIO concurrency lvl: " + threadsNum + " threads"
          ) 
      _ <- MyLogging.info(
            "console",
            "Listens: " + BINDING_SERVER_IP + ":" + SERVER_PORT + ", keep alive: " + KEEP_ALIVE + " ms"
          )

      //executor <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.platform.executor.asECES)
      //TODO - decide what to do here
      //executor     <- ZIO.attempt( java.util.concurrent.Executors.newCachedThreadPool() )

      ssl_context <- if (sslctx == null) buildSSLContext(TLS_PROTO, KEYSTORE_PATH, KEYSTORE_PASSWORD)
                    else ZIO.succeed(sslctx)

      address <- SocketAddress.inetSocketAddress(BINDING_SERVER_IP, SERVER_PORT)

      group <- AsynchronousChannelGroup( executor )

      _ <- group.openAsynchronousServerSocketChannelWith() { srv =>
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
                        AsynchronousServerTlsByteChannel(channel, ssl_context)(c => processor(new TlsChannel(c.keepAlive(KEEP_ALIVE))))
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
  def runWithSSLContext(proc: Channel => ZIO[ MyEnv, Exception, Unit], sslContext: SSLContext) = {

    processor = proc

    val cores = Runtime.getRuntime().availableProcessors();

    val es   = java.util.concurrent.Executors.newFixedThreadPool( cores )  
    val ec         = ExecutionContext.fromExecutor(  es  ) 
    val zio_executor = Executor.fromExecutionContext( 1024 )(ec )

    val rtc = RuntimeConfig.default.copy( executor = zio_executor )

    val T = myAppLogic( es, cores, sslContext ).fold(e => {
      e.printStackTrace(); zio.ExitCode(1)
    }, _ => zio.ExitCode(0))

     T.withRuntimeConfig( rtc )
  }
  
  ///////////////////////////////////////////////////
  //al executor = Executor.fromExecutionContext(1024)(ExecutionContext.global)
  //////////////////////////////////////////////////
  def run(proc: Channel => ZIO[ MyEnv, Exception, Unit]) = {

    processor = proc

    val cores = Runtime.getRuntime().availableProcessors();

    val es   = java.util.concurrent.Executors.newFixedThreadPool( cores )  
    val ec   = ExecutionContext.fromExecutor( es ) 
    val zio_executor = Executor.fromExecutionContext( 1024 )(ec )
   
    val rtc = RuntimeConfig.default.copy( executor = zio_executor )
  

    val T = myAppLogic(es, cores ).fold(e => {
      e.printStackTrace(); zio.ExitCode(1)
    }, _ => zio.ExitCode(0))

    T.withRuntimeConfig( rtc )

  }

  def buildSSLContext(protocol: String, JKSkeystore: String, password: String): ZIO[Any, Exception, SSLContext] = {

    //resource close - TODO

    val test = attemptBlocking {

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
      _ <- ZIO.succeed(terminate)
      //kick it one last time
      c <- clients.HttpConnection
            .connect(s"https://localhost:$SERVER_PORT", null, tlsBlindTrust = false, s"$KEYSTORE_PATH", s"$KEYSTORE_PASSWORD")
      response <- c.send(clients.ClientRequest(zhttp.Method.GET, "/"))

      svc <- MyLogging.logService
      _   <- svc.shutdown

    } yield ()

}
