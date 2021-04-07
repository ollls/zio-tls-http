package example

import zio.ZIO
import zio.ZEnv
import zhttp._
import zhttp.dsl._
import zio.Has

import java.time.ZonedDateTime

import zio.blocking._
import zhttp.HttpRoutes.WebFilterProc
import Method._

import zio.json._
import zio.Chunk

import MyLogging.MyLogging

object param1 extends QueryParam("param1")
object param2 extends QueryParam("param2")

import zhttp.clients.util.SkipList

object DataBlock {

  implicit val decoder: JsonDecoder[DataBlock] = DeriveJsonDecoder.gen[DataBlock]
  implicit val encoder: JsonEncoder[DataBlock] = DeriveJsonEncoder.gen[DataBlock]

}

case class DataBlock(val name: String, val address: String, val colors: Chunk[String])

object myServer extends zio.App {


  val sylo = new SkipList[String]

  HttpRoutes.defaultFilter(
    (_) => ZIO(Response.Ok.hdr("default_PRE_Filter" -> "to see me use print() method on headers"))
  )
  HttpRoutes.defaultPostProc(r => r.hdr("default_POST_Filter" -> "to see me check response in browser debug tool"))

  val ROOT_CATALOG = "/Users/user/web_root"

  //pre proc examples, aka web filters
  val proc0 = WebFilterProc((_) => ZIO(Response.Error(StatusCode.NotImplemented)))

  val proc1 = WebFilterProc(
    (_) => ZIO(Response.Ok.hdr("Injected-Header-Value" -> "1234").hdr("Injected-Header-Value" -> "more"))
  )

  val proc11 = WebFilterProc(
    (_) =>
      for {
        a <- ZIO.access[Has[String]](attr => attr)
      } yield (Response.Ok.hdr("StringFromEnv" -> a.get))
  )

  val proc2 = WebFilterProc(
    req =>
      ZIO {
        if (req.headers.getMval("Injected-Header-Value").exists(_ == "1234"))
          Response.Ok.hdr("Injected-Header-Value" -> "CheckPassed")
        else Response.Error(StatusCode.Forbidden)
      }
  )

  val proc3 = proc1 <> proc2 <> proc11 //combine two web filters into one

  //post PROC TO propagate extra headers
  val openCORS: HttpRoutes.PostProc = (r) =>
    r.hdr(("Access-Control-Allow-Origin" -> "*"))
      .hdr(("Access-Control-Allow-Method" -> "POST, GET, PUT"))

  //////////////////////////////////
  def run(args: List[String]) = {

    //Web filter fires first, always returns not implemented
    val route_with_filter = HttpRoutes.ofWithFilter(proc0) {
      case GET -> Root / "noavail" => ZIO(Response.Ok.asTextBody("OK "))
    }

    val ws_route2 = HttpRoutes.of { req: Request =>
      {
        req match {
          case GET -> Root / "websocket" =>
            if (req.isWebSocket) {
              val session = Websocket();
              session.accept(req) *>
                session.process_io(
                  req,
                  in => {
                    /* expect text or cont */
                    if (in.opcode == WebSocketFrame.BINARY) ZIO(WebSocketFrame.Close())
                    else {
                      zio.console.putStrLn("ABC> " + new String(in.data.toArray)) *>
                        ZIO(WebSocketFrame.Text("Hello From Server", true))
                    }
                  }
                )
            } else ZIO(Response.Error(StatusCode.NotFound))
        }
      }
    }

    val app_route_pre_post_filters = HttpRoutes.ofWithFilter(proc3, openCORS) { req =>
      req match {
        case GET -> Root / "print" =>
          MyLogging.trace("console", "Hello from app") *>
            ZIO(Response.Ok.asTextBody(req.headers.printHeaders))
        case GET -> Root / "Ok" => ZIO(Response.Ok)
      }
    }

    val app_route_JSON = HttpRoutes.ofWithFilter(proc1) {

      case req @ POST -> Root / "chunked" =>
        req.bodyAsText.map(text => Response.Ok.asTextBody(req.headers.printHeaders + "\n\n\n" + text))

      case POST -> Root / "container" / StringVar(name) =>
        sylo.u_add(name).map(b => Response.Ok.asTextBody(b.toString()))

      case GET -> Root / "container" =>
        ZIO(
          Response.Ok.asTextBody(
            sylo.debug_print(new StringBuilder).toString + "\n\n" + sylo.debug_print_layers(new StringBuilder).toString
          )
        )

      case DELETE -> Root / "container" / StringVar(name) =>
        sylo.u_remove(name).map(b => Response.Ok.asTextBody(b.toString()))
      //ZIO(Response.Ok.asTextBody( sylo.remove( name ).toString ) )

      case GET -> Root / "test" =>
        ZIO(Response.Ok.asJsonBody(DataBlock("Thomas", "1001 Dublin Blvd", Chunk("red", "blue", "green"))))

      case req @ POST -> Root / "test" =>
        for {
          _  <- ZIO( println( "ssssssss") )
          db <- req.fromJSON[DataBlock]
        } yield (Response.Ok.asTextBody(s"JSON for ${db.name} accepted"))

    }

    val app_route_cookies_and_params = HttpRoutes.of { req: Request =>
      {
        req match {

          case req @ GET -> Root / "health" => ZIO(Response.Ok.asTextBody("Health Check is OK"))

          case req @ GET -> Root / "app" / StringVar(userId1) / "get" => ZIO(Response.Ok.asTextBody(userId1))

          case req @ POST -> Root / "app" / "update" =>
            for {
              text <- req.bodyAsText
              _    <- ZIO(println(req.bodyAsText + "\n\n" + req.headers.printHeaders))
            } yield (Response.Ok)

          case req @ GET -> Root / "complex_param" =>
            val q = Option(req.uri.getQuery())
            ZIO(Response.Ok.asTextBody("java.URL.getQuery() returns: " + q.getOrElse("empty string")))

          case req @ GET -> Root / "hello" / "1" / "2" / "user2" :? param1(test) :? param2(test2) =>
            ZIO(Response.Ok.asTextBody("param1=" + test + "  " + "param2=" + test2))

          case GET -> Root / "hello" / "user" / StringVar(userId) :? param1(par) =>
            val headers = Headers("procid" -> "header_value_from_server", "Content-Type" -> ContentType.Plain.toString)

            val c1 = Cookie("testCookie", "ABCD", secure = true)
            val c2 = Cookie("testCookie2", "ABCDEFG", secure = false)
            val c3 =
              Cookie("testCookie3", "1A8BD0FC645E0", secure = false, expires = Some(ZonedDateTime.now.plusHours(5)))

            ZIO(
              Response.Ok
                .hdr(headers)
                .cookie(c1)
                .cookie(c2)
                .cookie(c3)
                .asTextBody(s"$userId with para1 $par")
            )

          case req @ GET -> Root / "files" / StringVar( filename ) => 

          for {
              stream <- FileUtils.httpFileStream( req, ROOT_CATALOG )
          } yield( Response.raw_stream( stream ) )
          
       

          //file submission to ROOT_CATALOG, entire file preloaded to memory
          case POST -> Root / "receiver" / StringVar(fileName) =>
            for {
              _ <- ZIO(println(req.contentType.value))
              b <- req.body
              _ <- ZIO(println(s"number of chunks = ${b.size}"))
              _ <- effectBlocking {
                    val infile = new java.io.FileOutputStream(ROOT_CATALOG + "/" + fileName)
                    infile.write(b.toArray)
                    infile.close()
                  }
            } yield (Response.Ok)

        }
      }
    }

    type MyEnv3 = MyLogging with Has[String]


    val myHttp = new TLSServer[MyEnv3](
      port = 8084,
      keepAlive = 4000,
      serverIP = "0.0.0.0",
      keystore = "keystore.jks",
      "password",
      tlsVersion = "TLSv1.2"
    )

   // val myHttp = new zhttp.TcpServer[MyEnv3]( 8080 )

    val myHttpRouter = new HttpRouter[MyEnv3](
      /* normal app routes */
      List( app_route_cookies_and_params, 
            app_route_JSON, 
            app_route_pre_post_filters, 
            ws_route2)
      /* channel ( file server) routes */
    )

    val AttributeLayer = ZIO.succeed("flag#1-1").toLayer

    myHttp
      .run(myHttpRouter.route)
      .provideSomeLayer[ZEnv with MyLogging](AttributeLayer)
      .provideSomeLayer[ZEnv](MyLogging.make(("console" -> LogLevel.Trace), ("access" -> LogLevel.Info)))
      .exitCode
  }
}
