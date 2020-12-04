package zhttp


import nio.channels.TLSChannelError
import zio.{ Chunk, IO, ZEnv, ZIO }
import scala.util.Try

import scala.io.Source
import scala.reflect.runtime.universe._



sealed trait      HTTPError             extends Exception
sealed case class BadInboundDataError() extends HTTPError
sealed case class HTTPHeaderTooBig()    extends HTTPError
sealed case class AccessDenied()        extends HTTPError
sealed case class ContentLenTooBig() extends HTTPError
sealed case class UpgradeRequest()   extends HTTPError
sealed case class MediaEncodingError( msg: String ) extends Exception( msg )


import zhttp.MyLogging._

object HttpRouter
{
  val _METHOD = "%%%http_method%%%" 
  val _PATH   = "%%%http_path%%%" 
  val _PROTO  = "%%%http_protocol%%%" 
}


class HttpRouter {
  //MAX SIZE for current implementation: 16676 ( one TLS app packet ) - do not exceed.
  val HTTP_HEADER_SZ  = 8096 * 2
  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100
                    
  private var appRoutes     = List[HttpRoutes[Response]]()

  private var channelRoutes = List[HttpRoutes[Response]]()

  //direct channel routes, where user route function is responsible
  //for reading the body and sending requests
  def addChannelRoute(rt: HttpRoutes[Response]): Unit =
    channelRoutes = rt :: channelRoutes

  //Normal auto map route where Request body is pre-read and
  //Response will be processed by server in post routine.
  def addAppRoute(rt: HttpRoutes[Response]): Unit =
    appRoutes = rt :: appRoutes

 
  def route( c : Channel ): ZIO[ ZEnv with MyLogging, Exception, Unit] = {
    val T : ZIO[ZEnv with MyLogging, Any, Unit] = for {
      req <- getHTTPRequest(c, false) /* don't try to read body of request now */

      res <- (for {
              response <- route_go(req, channelRoutes )
              _        <- response_processor(req, response._1 )

            } yield (response)).map(Option(_)).catchAll {
              case None    => IO.succeed(None)
              case Some(e) => IO.fail(e)
            }

      _ <- res match {
            case None =>
              finishBodyLoadForRequest(req).flatMap { r =>
                for {
                  response <- route_go(r, appRoutes ).catchAll {
                               case None =>
                                 IO.succeed( ( Response.Error(StatusCode.NotFound), HttpRoutes.defaultPostProc) ) 
                               case Some(e) => IO.fail(e)
                             }
                  //_.2 is postProc procedure _.1 is Response  
                  //response processor will get response with injected/extended attributes from PostProc         
                  response2 <- IO.effectTotal( response._2( response._1 ) )
                  _ <- response_processor(req, response2).catchAll { e =>
                        ZIO.fail( e )
                      }
                } yield (response)

              }
            case Some(response) => {
              IO.succeed(response)
            }
          }

    } yield ()


    //keep-alive until times out with exception
    T.forever.catchAll(ex => {
      ex match {

        case _ : UpgradeRequest =>
           MyLogging.trace( "console", "New websocket request spawned") 

        case _: java.nio.channels.InterruptedByTimeoutException =>
          //zio.console.putStrLn(">Keep-Alive expired.")
          MyLogging.trace( "console", "Connection closed")
        //c.close;

        case _: BadInboundDataError =>
          MyLogging.trace( "console", "Bad request, connection closed") *>
          ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad request.", true) *> IO.unit

        case _: HTTPHeaderTooBig =>
          MyLogging.error( "console", "HTTP header exceeds allowed limit") *>
          ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad HTTP header.", true) *> IO.unit

        case _: java.io.FileNotFoundException =>
          MyLogging.error( "console", "File not found") *>
          ResponseWriters.writeNoBodyResponse(c, StatusCode.NotFound, "Not found.", true) *> IO.unit

        case _: AccessDenied =>
          MyLogging.error( "console", "Access denied") *>
          ResponseWriters.writeNoBodyResponse(c, StatusCode.Forbidden, "Access denied.", true) *> IO.unit

        case _: scala.MatchError =>
          MyLogging.trace( "console", "Bad request(1)") *>
          ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad request (1).", true) *> IO.unit

        case _ : TLSChannelError =>
          MyLogging.trace( "console", "Remote peer closed connection")

        case _ : java.io.IOException =>
          MyLogging.trace( "console", "Remote peer closed connection (1)")
   
        case e : Exception =>
           MyLogging.error( "console", e.toString() ) *>
           ResponseWriters.writeNoBodyResponse(c, StatusCode.InternalServerError, "", true ) *> IO.unit
      }
    })
  }

  //route functions with response_processor()
  private def route_go(
    req: Request,
    appRoutes: List[HttpRoutes[Response] ]
  ): ZIO[ZEnv with MyLogging, Option[Exception], ( Response, HttpRoutes.PostProc )]=
     appRoutes match {
      case h :: tail => {
        for {
          response <- h.run(req).map( r => ( r, h.postProc )).catchAll {
                       case None => {
                         route_go(req, tail ) //None - we need another one
                       }
                       case Some(e) =>
                         ZIO.fail(Some(e))
                     }
        } yield ( response )
      }
      case Nil => ZIO.fail(None)
    }

 
  private def response_processor[A](req: Request, resp: Response ): ZIO[ZEnv with MyLogging, Exception, Int] =
    if (resp == NoResponse) {

      IO.succeed(0)

    } else {

      val contType = ContentType(resp.headers.get("content-type").getOrElse(""))
     
      val status = resp.code
      val body   = resp.body.getOrElse( Chunk[Byte]() )

      val T = if (status.isSuccess) {
        ResponseWriters.writeFullResponse(req.ch, resp, resp.code, new String(body.toArray), false)
      } else if (status == StatusCode.SeeOther)
        ResponseWriters.writeResponseRedirect(req.ch, new String(body.toArray))
      else if (status.isServerError)
        ResponseWriters.writeNoBodyResponse(req.ch, status, new String(body.toArray), true)
      else if (status == StatusCode.MethodNotAllowed)
        ResponseWriters.writeResponseMethodNotAllowed(req.ch, new String(body.toArray))
      else if (status == StatusCode.NotFound)
        ResponseWriters.writeNoBodyResponse(req.ch, status, new String(body.toArray), true)
      else if (status == StatusCode.SeeOther)
        ResponseWriters.writeResponseRedirect(req.ch, req.body.toString)
      else if (status == StatusCode.UnsupportedMediaType) {
        ResponseWriters.writeNoBodyResponse(req.ch, StatusCode.UnsupportedMediaType, "", true)
      } else ResponseWriters.writeNoBodyResponse(req.ch, status, new String(body.toArray), true)

      (Logs.log_access(req, status, body.size) *> T).refineToOrDie[Exception]
  
    }

  //TODO  expected size for read: contentLen = contentLen - bodyChunk.length
  private def rd_loop2(
    c: Channel,
    contentLen: Int,
    bodyChunk: Chunk[Byte]
  ): ZIO[ZEnv, Exception, zio.Chunk[Byte]] =
    if (contentLen > bodyChunk.length) {
      c.read.flatMap(chunk => rd_loop2(c, contentLen, bodyChunk ++ chunk))
    } else
      IO.succeed(bodyChunk)

  ////////////////////////////////////////////////////////////////
  def finishBodyLoadForRequest(req: Request): ZIO[ZEnv, Exception, Request] = {
    val contentLen = req.headers.get("content-length").getOrElse("0")

    rd_loop2(req.ch, contentLen.toInt, req.body).map(Request(req.headers, _, req.ch)).catchAll {
      case e => {
        ResponseWriters.writeNoBodyResponse(req.ch, StatusCode.BadRequest, "Invalid content length", true) *>
          IO.fail(e)
      }
    }

  }

  def read_http_header(
    c: Channel,
    hdr_size: Int,
    cb: Chunk[Byte] = Chunk[Byte]()
  ): ZIO[ZEnv, Exception, Chunk[Byte]] =
    for {
      nextChunk <- if (cb.size < hdr_size) c.read else IO.fail(new HTTPHeaderTooBig())
      pos       <- IO.effectTotal(new String(nextChunk.toArray).indexOf("\r\n\r\n"))
      resChunk  <- if (pos < 0) read_http_header(c, hdr_size, cb ++ nextChunk) else ZIO.effectTotal(cb ++ nextChunk)
    } yield (resChunk)

  ////////////////////////////////////////////////////////////////
  //assumption: HTTP_HEADER_SZ cannot be more then one TLS packet, which is 16 KB 
  private def getHTTPRequest(c: Channel, fetchBody: Boolean): ZIO[ZEnv, Exception, Request] = {
    val result = for {

      firstChunk <- read_http_header(c, HTTP_HEADER_SZ)

      source <- IO.effect(Source.fromBytes(firstChunk.toArray))
      _      <- IO.effect(source.withPositioning(true))
      lines  <- IO.effect(source.getLines())

      _ <- if (lines.hasNext == false) IO.fail(new BadInboundDataError())
          else IO.succeed(0).unit

      http_line = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r

      requestMap <- lines.next match {
                     case http_line(method, path, prot) =>
                       IO.effectTotal(Headers( HttpRouter._METHOD -> method, HttpRouter._PATH -> path, HttpRouter._PROTO -> prot))
                     case _ => IO.fail(new BadInboundDataError())

                   }

      attribute_pair = raw"(.{2,100}):\s+(.+)".r

      requestMapWithAttributes <- IO.effect {
                                   lines
                                     .takeWhile(!_.isEmpty)
                                     .foldLeft(requestMap)((map, line) => {

                                       line match {
                                         case attribute_pair(attr, value) => map + (attr.toLowerCase -> value)
                                       }

                                     })
                                 }

      //todo: optimize - not good to scan everything again with indexOf
      pos0 = new String(firstChunk.toArray).indexOf("\r\n\r\n")
      pos  = pos0 + 4

                  

      contentLen <-     if( pos0 == -1 )  ZIO.fail( new HTTPHeaderTooBig ) 
                        else IO.effect {
                          requestMapWithAttributes.get("content-length").getOrElse("0")
                        }  
                   
                     
       contentLenL <- ZIO.fromTry( Try( contentLen.toLong ) )

      _          <- if ( contentLenL > MAX_ALLOWED_CONTENT_LEN ) ZIO.fail( new ContentLenTooBig ) else ZIO.unit        

      bodyChunk <- if (fetchBody)
                    rd_loop2(c, contentLen.toInt, firstChunk.drop(pos))
                  else
                    IO.succeed {
                      firstChunk.drop(pos)
                    }

    } yield (Request(requestMapWithAttributes, bodyChunk, c))

    result.refineToOrDie[Exception]
  }

}
