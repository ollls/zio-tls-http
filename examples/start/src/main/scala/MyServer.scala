package example

import zio.ZIO
import zio.ZLayer
import zhttp._
import zhttp.dsl._

import java.time.ZonedDateTime

import zhttp.HttpRoutes.WebFilterProc
import Method._

import zio.json._
import zio.Chunk
import zio.stream.ZStream

import zio.ZIOAppDefault

import zio.ZIOApp
import zio.ZEnvironment

object param1 extends QueryParam("param1")
object param2 extends QueryParam("param2")

import zhttp.clients.util.SkipList

import example.myServer.validateEnv
import zio.logging.backend.SLF4J

object DataBlock {

  implicit val decoder: JsonDecoder[DataBlock] = DeriveJsonDecoder.gen[DataBlock]
  implicit val encoder: JsonEncoder[DataBlock] = DeriveJsonEncoder.gen[DataBlock]

}

case class DataBlock(val name: String, val address: String, val colors: Chunk[String])

object myServer extends zio.ZIOAppDefault {

  override val bootstrap = zio.Runtime.removeDefaultLoggers ++ SLF4J.slf4j ++ zio.Runtime.enableWorkStealing

  // type Environment = MyLogging with String

  // override type Environment = zio.ZEnv

  val sylo = new SkipList[String]

  HttpRoutes.defaultFilter((_) => ZIO.succeed(Response.Ok().hdr("default_PRE_Filter" -> "to see me use print() method on headers")))
  HttpRoutes.defaultPostProc(r => r.hdr("default_POST_Filter" -> "to see me check response in browser debug tool"))

  // val ROOT_CATALOG = "/Users/ostry/MyProjects"
  val ROOT_CATALOG = "/Users/ostrygun/web_root"

  // pre proc examples, aka web filters
  val proc0 = WebFilterProc((_) => ZIO.succeed(Response.Error(StatusCode.NotImplemented)))

  val proc1 = WebFilterProc((_) => ZIO.succeed(Response.Ok().hdr("Injected-Header-Value" -> "1234").hdr("Injected-Header-Value" -> "more")))

  val proc11 = WebFilterProc((_) =>
    for {
      a <- ZIO.environmentWith[String](attr => attr)
    } yield (Response.Ok().hdr("StringFromEnv" -> a.get))
  )

  val proc2 = WebFilterProc(req =>
    ZIO.succeed {
      if (req.headers.getMval("Injected-Header-Value").exists(_ == "1234"))
        Response.Ok().hdr("Injected-Header-Value" -> "CheckPassed")
      else Response.Error(StatusCode.Forbidden)
    }
  )

  val proc3 = proc1 <> proc2 <> proc11 // combine two web filters into one

  // post PROC TO propagate extra headers
  val openCORS: HttpRoutes.PostProc = (r) =>
    r.hdr(("Access-Control-Allow-Origin" -> "*"))
      .hdr(("Access-Control-Allow-Method" -> "POST, GET, PUT"))

  //////////////////////////////////
  // def run(args: List[String]) = {
  // def run(args: List[String]) = {
  def run = {

    // Web filter fires first, always returns not implemented
    val route_with_filter = HttpRoutes.ofWithFilter(proc0) { case GET -> Root / "noavail" =>
      ZIO.succeed(Response.Ok().asTextBody("OK "))
    }

    val ws_stream = HttpRoutes.of { case req @ GET -> Root / "ws-test" =>
      if (req.isWebSocket) {
        val wsctx     = Websocket()
        val ws_stream = wsctx.receiveTextAsStream(req)
        for {
          _ <- wsctx.accept(req)
          _ <- ws_stream.foreach { frame =>
            ZIO.logDebug("Data from websocket <<< " + new String(frame.data.toArray)) *>
              wsctx.sendAsTextStream(
                req,
                ZStream("Websocket Rocks, this will be one packet with size: " + Websocket.WS_PACKET_SZ)
              )
          }
        } yield (Response.Ok())
      } else ZIO.succeed(Response.Error(StatusCode.NotFound))
    }

    val app_route_pre_post_filters = HttpRoutes.ofWithFilter(proc3, openCORS) { req =>
      req match {
        case GET -> Root / "print" =>
          ZIO.logTrace("Hello from app") *>
            ZIO.succeed(Response.Ok().asTextBody(req.headers.printHeaders))
        case GET -> Root / "Ok" => ZIO.succeed(Response.Ok())
      }
    }

    val app_route_JSON = HttpRoutes.ofWithFilter(proc1) {

      case req @ POST -> Root / "chunked" =>
        req.bodyAsText.map(text => Response.Ok().asTextBody(req.headers.printHeaders + "\n\n\n" + text))

      case POST -> Root / "container" / StringVar(name) =>
        sylo.u_add(name).map(b => Response.Ok().asTextBody(b.toString()))

      case GET -> Root / "container" =>
        ZIO.succeed(
          Response
            .Ok()
            .asTextBody(
              sylo.debug_print(new StringBuilder).toString + "\n\n" + sylo.debug_print_layers(new StringBuilder).toString
            )
        )

      case DELETE -> Root / "container" / StringVar(name) =>
        sylo.u_remove(name).map(b => Response.Ok().asTextBody(b.toString()))
      // ZIO(Response.Ok.asTextBody( sylo.remove( name ).toString ) )

      case GET -> Root / "test" =>
        ZIO.succeed(Response.Ok().asJsonBody(DataBlock("Thomas", "1001 Dublin Blvd", Chunk("red", "blue", "green"))))

      case req @ POST -> Root / "test" =>
        for {
          db <- req.fromJSON[DataBlock]
        } yield (Response.Ok().asTextBody(s"JSON for ${db.name} accepted"))

    }

    val app_route_cookies_and_params: HttpRoutes[Any] = HttpRoutes.of { (req: Request) =>
      {
        req match {

          case req @ GET -> Root / "health" => ZIO.succeed(Response.Ok()) //.asTextBody("Health Check is OK"))

          case req @ GET -> Root / "app" / StringVar(userId1) / "get" => ZIO.succeed(Response.Ok().asTextBody(userId1))

          case req @ POST -> Root / "app" / "update" =>
            for {
              text <- req.bodyAsText
              _    <- ZIO.succeed(println(text + "\n\n" + req.headers.printHeaders))
            } yield (Response.Ok())

          case req @ GET -> Root / "complex_param" =>
            val q = Option(req.uri.getQuery())
            ZIO.succeed(Response.Ok().asTextBody("java.URL.getQuery() returns: " + q.getOrElse("empty string")))

          case req @ GET -> Root / "hello" / "1" / "2" / "user2" :? param1(test) :? param2(test2) =>
            ZIO.succeed(Response.Ok().asTextBody("param1=" + test + "  " + "param2=" + test2))

          case GET -> Root / "hello" / "user" / StringVar(userId) :? param1(par) =>
            val headers = Headers("procid" -> "header_value_from_server", "Content-Type" -> ContentType.Plain.toString)

            val c1 = Cookie("testCookie", "ABCD", secure = true)
            val c2 = Cookie("testCookie2", "ABCDEFG", secure = false)
            val c3 =
              Cookie("testCookie3", "1A8BD0FC645E0", secure = false, expires = Some(ZonedDateTime.now.plusHours(5)))

            ZIO.succeed(
              Response
                .Ok()
                .hdr(headers)
                .cookie(c1)
                .cookie(c2)
                .cookie(c3)
                .asTextBody(s"$userId with para1 $par")
            )

          case req @ GET -> "pub" /: _ =>
            for {
              stream <- FileUtils.httpFileStream(req, ROOT_CATALOG)
            } yield (Response.raw_stream(stream))

          // ROOT_CATALOG and remainig path within the catalog
          // curl https://localhost:8084/files2/chunked/files/picture.jpg --output out.jpg
          case req @ GET -> "files2" /: "chunked" /: remainig_path =>
            for {
              query <- ZIO.succeed(req.uri.getQuery)
              _ <- ZIO.logInfo(
                "Requested file: " + req.uri.getPath + " Query string:  " + query
              )
              path <- FileUtils.serverFilePath_(remainig_path, ROOT_CATALOG)
              str = ZStream.fromFile(path.toFile, 16000).mapChunks(Chunk.single(_))
            } yield (Response.Ok().asStream(str).transferEncoding("chunked"))

          // Just exact file name in the ROOT_CATALOG
          // curl https://localhost:8084/files2/picture1.jpg --output out2.jpg
          case GET -> Root / "files2" / StringVar(filename) =>
            for {
              path <- FileUtils.serverFilePath(filename, ROOT_CATALOG)
              str = ZStream.fromFile(path.toFile).mapChunks(Chunk.single(_))
            } yield (Response.Ok().asStream(str))

          // file submission to ROOT_CATALOG, entire file preloaded to memory
          case POST -> Root / "receiver" / StringVar(fileName) =>
            for {
              _ <- ZIO.succeed(println(req.contentType.value))
              b <- req.body
              _ <- ZIO.succeed(println(s"bytes = ${b.size}"))
              _ <- ZIO.attemptBlocking {
                val infile = new java.io.FileOutputStream(ROOT_CATALOG + "/" + fileName)
                infile.write(b.toArray)
                infile.close()
              }
            } yield (Response.Ok())

        }
      }
    }



    
    val myHttp2 = new TLSServer[String](
      port = 8084,
      keepAlive = 4000,
      // serverIP = "0.0.0.0",
      serverIP = "127.0.0.1",
      keystore = "keystore.jks",
      "password",
      tlsVersion = "TLSv1.2"
    )
    
    /*
    val myHttp = new SyncTLSSocketServer[String](
      port = 8084,
      keepAlive = 4000,
      // serverIP = "0.0.0.0",
      serverIP = "127.0.0.1",
      keystore = "keystore.jks",
      "password",
      tlsVersion = "TLSv1.2"
    )*/

    // val myHttp = new zhttp.TcpServer[MyEnv3]( 8080 )

    val myHttpRouter = new HttpRouter(
      /* normal app routes */
      List(
      app_route_cookies_and_params, 
      app_route_JSON, 
      app_route_pre_post_filters, 
      ws_stream)
      /* channel ( file server) routes */
    )

    val AttributeLayer = ZLayer.fromZIO(ZIO.succeed("flag#1-1"))

    val test = myHttpRouter.route
  

    //val test: IOChannel => Chunk[Byte] => ZIO[String, Throwable, Unit]

    val R1 = myHttp2
      .run(test)
      .provideSomeLayer(AttributeLayer)
    R1
  }

}
