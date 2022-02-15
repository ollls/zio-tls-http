package zhttp

import zio.{ IO, ZEnv, ZIO, ZManaged }
import nio.SocketAddress

import nio._
import nio.channels._

import zio.ExitCode

////{ Executors, ExecutorService, ThreadPoolExecutor }

class TcpServer[MyEnv <: MyLogging.Service](port: Int, keepAlive: Int = 2000, serverIP: String = "0.0.0.0") {

  val BINDING_SERVER_IP = serverIP //make sure certificate has that IP on SAN's list
  val KEEP_ALIVE: Long  = keepAlive //ms, good if short for testing with broken site's snaphosts with 404 pages
  val SERVER_PORT       = port

  private var processor: Channel => ZIO[ZEnv with MyEnv, Exception, Unit] = null

  private var f_terminate = false
  def terminate           = f_terminate = true
  def isTerminated        = f_terminate

  /////////////////////////////////
  def myAppLogic: ZIO[ZEnv with MyEnv, Throwable, ExitCode] =
    for {

      metr <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.runtimeConfig.executor.unsafeMetrics ) 

      _ <- MyLogging.info("console", s"HTTP Service started. ZIO concurrency lvl: " + metr.get.concurrency + " threads")
      _ <- MyLogging.info(
            "console",
            "Listens TCP: " + BINDING_SERVER_IP + ":" + SERVER_PORT + ", keep alive: " + KEEP_ALIVE + " ms"
          )
      //executor <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.runtimeConfig.executor.asExecutionContext. )
      executor     <- ZIO.attempt( java.util.concurrent.Executors.newCachedThreadPool() ) // .newFixedThreadPool(4) )
      address <- SocketAddress.inetSocketAddress(BINDING_SERVER_IP, SERVER_PORT)
      group   <- AsynchronousChannelGroup(executor)
      _ <- group.openAsynchronousServerSocketChannel().use { srv =>
            {
              for {
                _ <- srv.bind(address)

                loop = srv.accept2
                  .flatMap(
                    channel =>
                      channel.remoteAddress.flatMap(c => {
                        MyLogging.debug("console", "Connected: " + c.get.toInetSocketAddress.address.canonicalHostName)
                      }) *>
                        ZManaged
                          .acquireReleaseWith(ZIO.attempt(new TcpChannel(channel.keepAlive(KEEP_ALIVE))))(Channel.close(_).orDie)
                          .use { c =>
                            processor(c).catchAll(e => MyLogging.error("console", e.toString) *> IO.succeed(0))
                          }
                          .fork
                  )
                _ <- loop.repeatUntil(_ => isTerminated)

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

  def stop =
    for {
      _ <- ZIO.succeed(terminate)
      //kick it one last time
      c <- clients.HttpConnection
            .connect(s"http://localhost:$SERVER_PORT")
      response <- c.send(clients.ClientRequest(zhttp.Method.GET, "/"))

      svc <- MyLogging.logService
      _   <- svc.shutdown

    } yield ()

}
