package EDGZ

import zhttp.TLSServer
import zhttp.clients._
import zhttp.HttpRoutes
import zhttp.dsl._
import zhttp.MyLogging._
import zhttp.MyLogging
import zhttp.LogLevel
import zhttp.HttpRouter
import zhttp.StatusCode
import zhttp.clients.ResPool.ResPool

import zhttp.Response
import zhttp.Method._

import zio.ZIO
import zio.ZEnv


import clients.ResPoolCache
import clients.ResPoolCache.ResPoolCache

object EDGZ extends zio.App {

  def run(args: List[String]) = {

    val route1 = HttpRoutes.of {

      case GET -> Root / "service" / "test" / StringVar( key0 ) =>
        for {
          key <- if ( key0 == null) ZIO("") else ZIO( key0 )
          res <- ResPoolCache.get[String, String, Unit](key)
        } yield (Response.Ok.asTextBody(key))

      case GET -> Root / "service" / "stat" =>
        for {
          out <- ResPoolCache.info[String, String, Unit]

        } yield (Response.Ok.asTextBody(out))

      case GET -> Root / "service" / "health" =>
        ZIO(Response.Ok.asTextBody("Health Check Ok"))

    }

    type MyEnv = MyLogging with ResPool[Unit] with ResPoolCache[String, String, Unit]

    val edgz_Http    = new TLSServer[MyEnv]
    val myHttpRouter = new HttpRouter[MyEnv]

    myHttpRouter.addAppRoute( route1 )

    edgz_Http.KEYSTORE_PATH = "keystore.jks"
    edgz_Http.KEYSTORE_PASSWORD = "password"
    edgz_Http.TLS_PROTO = "TLSv1.2"
    edgz_Http.BINDING_SERVER_IP = "0.0.0.0"
    edgz_Http.KEEP_ALIVE = 2000
    edgz_Http.SERVER_PORT = 8443

    //Layers
    val logger_L = MyLogging.make(("console" -> LogLevel.Trace), ("access" -> LogLevel.Info))

    val dummyConPool_L = ResPool.make[Unit](timeToLiveMs = 20 * 1000, () => (), (Unit) => ())

    val cache_L =
      ResPoolCache.make(timeToLiveMs = 60 * 1000, limit = 5000000, (u: Unit, number: String) => ZIO.succeed(number))

    //all layers visibe
    edgz_Http
      .run(myHttpRouter.route)
      .provideSomeLayer[ZEnv with MyLogging with ResPool[Unit]](cache_L)
      .provideSomeLayer[ZEnv with MyLogging](dummyConPool_L)
      .provideSomeLayer[ZEnv](logger_L)
      .exitCode
  }

}
