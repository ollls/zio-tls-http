package example

import zio.ZIO
import zio.ZEnv
import zhttp._

import java.time.ZonedDateTime

import zio.blocking._
import zhttp.HttpRoutes.WebFilterProc
import Method._

import zio.json._
import zio.Chunk

object param1 extends QueryParam("param1")


object DataBlock {

  implicit val decoder: JsonDecoder[DataBlock] = DeriveJsonDecoder.gen[DataBlock]
  implicit val encoder: JsonEncoder[DataBlock] = DeriveJsonEncoder.gen[DataBlock]

}

case class DataBlock(val name: String, val address: String, val colors : Chunk[String] )


object myServer extends zio.App {

  HttpRoutes.defaultFilter( (_) => ZIO( Response.Ok().hdr( "default_PRE_Filter" -> "to see me use print() method on headers") ) )
  HttpRoutes.defaultPostProc( r => r.hdr( "default_POST_Filter" -> "to see me check response in browser debug tool") )

  val ROOT_CATALOG = "/app/web_root"

  val myHttpRouter = new HttpRouter

  //pre proc examples, aka web filters
  val proc0 = WebFilterProc((_) => ZIO(Response.Error(StatusCode.NotImplemented)))

  val proc1 = WebFilterProc(
    (_) => ZIO(Response.Ok.hdr("Injected-Header-Value" -> "1234").hdr("Injected-Header-Value" -> "more"))
  )

  val proc2 = WebFilterProc(
    req =>
      ZIO {
        if (req.headers.getMval("Injected-Header-Value").exists(_ == "1234"))
          Response.Ok.hdr("Injected-Header-Value" -> "CheckPassed")
        else Response.Error(StatusCode.Forbidden)
      }
  )

  val proc3 = proc1 <> proc2   //combine two web filters into one

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
                session.accept( req ) *>
                session.process_io( req, in => {
                    /* expect text or cont */
                    if ( in.opcode == WebSocketFrame.BINARY ) ZIO( WebSocketFrame.Close() )
                    else {
                       zio.console.putStrLn( "ABC> " + new String( in.data.toArray )) *>
                       ZIO( WebSocketFrame.Text( "Hello From Server", true ) )
                    }   
                } )
            } else  ZIO(Response.Error(StatusCode.NotFound))
       }
    } 
    }  
   
    //example of static document server
    //Raw Route with packet reads, without fetching content into memory
    val document_server = HttpRoutes.of { req: Request =>
      {
        req match {
          case GET -> Root =>
            for {
              _ <- myHttpRouter.finishBodyLoadForRequest(req) //we need to finish reading to request.body for Raw Routes
              res <- ZIO(
                      Response
                        .Error(StatusCode.SeeOther)
                        .asTextBody("zio_doc/index.html")
                    )
            } yield (res)

          //opens up everything under ROOT_CATALOG/web  
          case GET -> "web" /: _ =>
            myHttpRouter.finishBodyLoadForRequest(req) *>
              FileUtils.loadFile(req, ROOT_CATALOG)

          //opens up everythingunder ROOT_CATALOG/web2    
          case GET -> Root / "web2" / _ =>
            myHttpRouter.finishBodyLoadForRequest(req) *>
              FileUtils.loadFile(req, ROOT_CATALOG)

          //zio documentation is here    
          case GET -> "zio_doc" /: _ =>
            myHttpRouter.finishBodyLoadForRequest(req) *>
              FileUtils.loadFile(req, ROOT_CATALOG)

          //how to write file to disk, without prefetching it to memory     
          //we don't need finishBodyLoadForRequest() here
          case POST -> Root / "save" / StringVar(_) =>
            FileUtils.saveFile(req, ROOT_CATALOG)

        }
      }
    }

    val app_route_pre_post_filters = HttpRoutes.ofWithFilter(proc3, openCORS) { req =>
      req match {
        case GET -> Root / "print" =>
          ZIO(Response.Ok.asTextBody(req.headers.printHeaders))
        case GET -> Root / "Ok" => ZIO( Response.Ok )  
      }
    }

    val app_route_JSON = HttpRoutes.ofWithFilter(proc1) { 

       case GET -> Root / "test2" =>
         ZIO(Response.Ok.asTextBody( "Health Check" ) )

        
       case GET -> Root / "test" =>
         ZIO(Response.Ok.asJsonBody( DataBlock("Thomas", "1001 Dublin Blvd", Chunk( "red", "blue", "green" ) ) ) )
                                                
       case req @ POST -> Root / "test" =>
         ZIO.effect { //need to wrap up everything in the effect to have proper error handling
           val db : DataBlock = req.fromJSON[DataBlock]
           val name = db.name
           Response.Ok.asTextBody( s"JSON for $name accepted" )     
         }                                  
      }   
    

    val app_route_cookies_and_params = HttpRoutes.of { req: Request =>
      {
        req match {

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
          
          //file submission to ROOT_CATALOG, entire file preloaded to memory  
          case POST -> Root / "receiver" / StringVar(fileName) =>
            effectBlocking {
              println(req.contentType.value)
              println("number of chunks = " + req.body.size)
              val infile = new java.io.FileOutputStream(ROOT_CATALOG + "/" + fileName)

              infile.write(req.body.toArray)

              infile.close()
            } *> ZIO(Response.Ok)

        }
      }
    }

    //app routes
    myHttpRouter.addAppRoute( app_route_cookies_and_params )
    myHttpRouter.addAppRoute( app_route_JSON )
    myHttpRouter.addAppRoute(app_route_pre_post_filters )

    myHttpRouter.addChannelRoute( document_server )
    myHttpRouter.addChannelRoute( route_with_filter )

    myHttpRouter.addAppRoute( ws_route2 )

 
    val myHttp = new TLSServer
    //server
    myHttp.KEYSTORE_PATH = "keystore.jks"
    myHttp.KEYSTORE_PASSWORD = "password"
    myHttp.TLS_PROTO = "TLSv1.2"         //default TLSv1.2 in JDK8
    myHttp.BINDING_SERVER_IP = "0.0.0.0" //make sure certificate has that IP on SAN's list
    myHttp.KEEP_ALIVE = 2000             //ms, good if short for testing with broken site's snaphosts with 404 pages
    myHttp.SERVER_PORT = 8084

    myHttp
      .run(myHttpRouter.route)
      .provideSomeLayer[ZEnv](MyLogging.make(("console" -> LogLevel.Trace), ("access" -> LogLevel.Info)))
      .exitCode
  }
}
