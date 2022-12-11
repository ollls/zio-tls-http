package zhttp

import zio.{IO, ZIO, Chunk}
import zio.ExitCode
import zhttp.netio._

class TcpServer[MyEnv <: MyLogging.Service](port: Int, keepAlive: Int = 2000, serverIP: String = "0.0.0.0") {

  val BINDING_SERVER_IP = serverIP  // make sure certificate has that IP on SAN's list
  val KEEP_ALIVE: Long  = keepAlive // ms, good if short for testing with broken site's snaphosts with 404 pages
  val SERVER_PORT       = port

  //private var processor: IOChannel => ZIO[MyEnv, Throwable, Unit] = null

  private var f_terminate = false
  def terminate           = f_terminate = true
  def isTerminated        = f_terminate

  def hostName(address: java.net.SocketAddress) = {
    val ia = address.asInstanceOf[java.net.InetSocketAddress]
    ia.getHostString()
  }

  /////////////////////////////////
  def myAppLogic( processor : IOChannel => Chunk[Byte] => ZIO[MyEnv, Throwable, Unit]): ZIO[MyEnv, Throwable, ExitCode] =
    for {

      _ <- MyLogging.info(
        "console",
        "Listens TCP: " + BINDING_SERVER_IP + ":" + SERVER_PORT + ", keep alive: " + KEEP_ALIVE + " ms"
      )
   
      executor <- ZIO.attempt(java.util.concurrent.Executors.newCachedThreadPool()) // .newFixedThreadPool(4) )
      addr     <- ZIO.attempt(new java.net.InetSocketAddress(BINDING_SERVER_IP, SERVER_PORT))
      group    <- ZIO.attempt(java.nio.channels.AsynchronousChannelGroup.withThreadPool(executor))

      server_ch <- ZIO.attempt(group.provider().openAsynchronousServerSocketChannel(group).bind(addr))
      _         <- ZIO.attempt(server_ch.bind(addr))

      accept = for {
        channel    <- TCPChannel.accept(server_ch)
        remoteAddr <- ZIO.attempt(channel.ch.getRemoteAddress())
        _          <- MyLogging.info("console", "Connected: " + hostName(remoteAddr))
      } yield (channel)

      _ <- accept
        .flatMap(c =>
          ZIO.scoped {
            ZIO.acquireReleaseWith(ZIO.attempt(c))(_.close().orDie) { c =>
              processor(c)(Chunk.empty[Byte]).catchAll(e => MyLogging.error("console", e.toString) *> ZIO.succeed(0))
            }
          }.fork
        ) .catchAll( e =>  MyLogging.error( "console", e.toString() ) ).repeatUntil(_ => isTerminated)

    } yield (ExitCode(0))


  //////////////////////////////////////////////////
  def run( appRoutes: HttpRoutes[MyEnv]*)  =
  {  
    val rtr = new HttpRouter( appRoutes.toList )

    val T = myAppLogic(rtr.route).fold(
      e => {
        e.printStackTrace(); zio.ExitCode(1)
      },
      _ => zio.ExitCode(0)
    )
    T
  }  

  @deprecated("Use run() with list HttRoutes directly" )
  def run(proc: IOChannel => Chunk[Byte] => ZIO[MyEnv, Throwable, Unit])  = {

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

      svc <- MyLogging.logService
      _   <- svc.shutdown

    } yield ()

}
