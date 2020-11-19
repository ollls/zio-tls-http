package zhttp

import zio.{ IO, ZEnv, ZIO, ZManaged }
import nio.SocketAddress

import nio._
import nio.channels._

import zio.Schedule
import zio.ExitCode

import zhttp.MyLogging._

////{ Executors, ExecutorService, ThreadPoolExecutor }

class TcpServer {

  var BINDING_SERVER_IP = "127.0.0.1" //make sure certificate has that IP on SAN's list
  var KEEP_ALIVE: Long  = 15000 //ms, good if short for testing with broken site's snaphosts with 404 pages
  var SERVER_PORT       = 8084

  private var processor: Channel => ZIO[ZEnv with MyLogging.MyLogging, Exception, Unit] = null

  /////////////////////////////////
  def myAppLogic: ZIO[ZEnv with MyLogging, Throwable, ExitCode] =
    for {

      metr <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.platform.executor.metrics)

      _ <- MyLogging.info("console", s"HTTP Service started. ZIO concurrency lvl: " + metr.get.concurrency + " threads")
      _ <- MyLogging.info(
            "console",
            "Listens TCP: " + BINDING_SERVER_IP + ":" + SERVER_PORT + ", keep alive: " + KEEP_ALIVE + " ms"
          )

      executor <- ZIO.runtime.map((runtime: zio.Runtime[Any]) => runtime.platform.executor.asECES)

      address <- SocketAddress.inetSocketAddress(BINDING_SERVER_IP, SERVER_PORT)

      group <- AsynchronousChannelGroup(executor)

      _ <- group.openAsynchronousServerSocketChannel().use { srv =>
            {
              for {

                _ <- srv.bind(address)

                loop = srv.accept2
                  .flatMap(
                    channel =>
                      channel.remoteAddress.flatMap(c => {
                        MyLogging.trace("console", "Connected: " + c.get.toInetSocketAddress.address.canonicalHostName)
                      }) *>
                        ZManaged
                          .make(ZIO.effect(new TcpChannel(channel.keepAlive(KEEP_ALIVE))))(_.close.orDie)
                          .use { c =>
                            processor(c).catchAll(_ => IO.succeed(0))
                          }
                          .fork
                  )
                _ <- loop.repeat(Schedule.forever)

              } yield ()
            }
          }

    } yield (ExitCode(0))

  //////////////////////////////////////////////////
  def run(proc: Channel => ZIO[ZEnv with MyLogging.MyLogging, Exception, Unit]) = {

    processor = proc

    val T = myAppLogic.fold(e => {
      e.printStackTrace(); zio.ExitCode(1)
    }, _ => zio.ExitCode(0))

    T
  }

}
