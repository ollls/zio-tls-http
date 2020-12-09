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
import zio.Schedule
import zio.ExitCode

import zhttp.MyLogging._

////{ Executors, ExecutorService, ThreadPoolExecutor }

class TLSServer {

  var KEYSTORE_PATH     = "/app/keystore.jks"
  var KEYSTORE_PASSWORD = "password"
  var TLS_PROTO         = "TLS" //default TLSv1.2 in JDK8
  var BINDING_SERVER_IP = "127.0.0.1" //make sure certificate has that IP on SAN's list
  var KEEP_ALIVE: Long  = 15000 //ms, good if short for testing with broken site's snaphosts with 404 pages
  var SERVER_PORT       = 8084

  private var processor: Channel => ZIO[ZEnv with MyEnv, Exception, Unit] = null

  /////////////////////////////////
  def myAppLogic: ZIO[ZEnv with MyEnv, Throwable, ExitCode] =
    for {

      metr <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.platform.executor.metrics)

      _    <- MyLogging.info( "console", s"TLS HTTP Service started on " + SERVER_PORT + ", ZIO concurrency lvl: " + metr.get.concurrency + " threads")
      _    <- MyLogging.info( "console", "Listens: " + BINDING_SERVER_IP + ":" + SERVER_PORT + ", keep alive: " + KEEP_ALIVE + " ms")

      executor <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.platform.executor.asECES)

      ssl_context <- buildSSLContext(TLS_PROTO, KEYSTORE_PATH, KEYSTORE_PASSWORD)

      address <- SocketAddress.inetSocketAddress(BINDING_SERVER_IP, SERVER_PORT)

      group <- AsynchronousChannelGroup(executor)

      _ <- group.openAsynchronousServerSocketChannel().use { srv =>
            {
              for {

                _ <- srv.bind(address)

                loop = srv.accept2.flatMap(
                    channel =>
                        channel.remoteAddress.flatMap(  
                        c => MyLogging.trace( "console", 
                          "Connected: " + c.get.toInetSocketAddress.address.canonicalHostName ) ) *>
                      AsynchronousServerTlsByteChannel(channel, ssl_context)
                        .use(c => processor( new TlsChannel( c.keepAlive(KEEP_ALIVE ) )))
                        .catchAll( _ => {
                          //e.printStackTrace; println("***" + e.toString); /*group.shutdownNow *>*/
                          IO.succeed(0)
                        }) 
                        .fork ).catchAll( _ =>  IO.succeed(0) )
                  

                _ <- loop.repeat(Schedule.forever)

              } yield ()
            }
          }

    } yield (ExitCode(0))

  //////////////////////////////////////////////////
  def run(proc: Channel => ZIO[ZEnv with MyEnv, Exception, Unit]) = {

    processor = proc

    val T = myAppLogic.fold(e => {
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

}
