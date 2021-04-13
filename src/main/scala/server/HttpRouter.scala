package zhttp

import nio.channels.TLSChannelError
import zio.{ Chunk, IO, ZEnv, ZIO }

import zio.Has

import zio.stream.ZStream
import zio.stream.ZTransducer
import zio.stream.ZSink
import zio.ZRef

sealed trait HTTPError                              extends Exception
sealed case class BadInboundDataError()             extends HTTPError
sealed case class HTTPHeaderTooBig()                extends HTTPError
sealed case class AccessDenied()                    extends HTTPError
sealed case class ContentLenTooBig()                extends HTTPError
sealed case class UpgradeRequest()                  extends HTTPError
sealed case class MediaEncodingError(msg: String)   extends Exception(msg)
sealed case class ChunkedEncodingError(msg: String) extends Exception(msg)

object HttpRouter {
  val _METHOD = "%%%http_method%%%"
  val _PATH   = "%%%http_path%%%"
  val _PROTO  = "%%%http_protocol%%%"


  /*****************************************************************/
  //So the last chunk and 2 trailing headers might look like this:
  //0<CRLF>
  //Date:Sun, 06 Nov 1994 08:49:37 GMT<CRLF>
  //Content-MD5:1B2M2Y8AsgTpgAmY7PhCfg==<CRLF>
  //<CRLF>
  def chunkedDecode: ZTransducer[Any, Nothing, Byte, Chunk[Byte]] = {

    def parseTrail(in: Chunk[Byte], pos: Int): Boolean = {
      if (in.size - pos < 4) return false //more data
      if (in(pos) == '\r' && in(pos + 1) == '\n') {

        var idx       = pos + 2
        var bb: Byte  = 0
        var pbb: Byte = 0
        var splitAt   = 0
        var found     = false

        while (idx < in.size && found == false) {
          pbb = bb
          bb = in(idx)
          idx += 1
          if (bb == '\n' && pbb == '\r') {
            splitAt = idx - 2
            found = true
          }
        }

        if (found == false) return false //more data

        //we will ignore trailing block here, but it can be provided, right after empty block for further use.
        //TODO
        val trailingHeaders = new String(in.slice(pos + 2, splitAt).toArray)
        true
      } else throw new BadInboundDataError()
      true
    }

    def produceChunk(in: Chunk[Byte]): (Option[Chunk[Byte]], Chunk[Byte], Boolean) = {

      var bb: Byte      = 0
      var pbb: Byte     = 0
      var splitAt: Int  = 0
      var idx: Int      = 0
      var stop: Boolean = false

      //extract size
      while (idx < in.size && stop == false) {
        pbb = bb
        bb = in(idx)
        if (bb == '\n' && pbb == '\r') {
          splitAt = idx - 2 + 1
          stop = true
        } else {
          idx += 1
        }
      }

      val str_str = new String(in.slice(0, splitAt).toArray)

      val chunkSize = Integer.parseInt(new String(in.slice(0, splitAt).toArray), 16)

      if (chunkSize > 0 && chunkSize < in.size - idx + 2 + 1) {

        val chunk    = in.slice(splitAt + 2, splitAt + 2 + chunkSize)
        val leftOver = in.slice(splitAt + 2 + chunkSize + 2, in.size)

        (Some(chunk), leftOver, false)
      } else {
        if (chunkSize == 0) {
          if (parseTrail(in, splitAt) == true) (None, in, true)
          else (None, in, false) //more data
        } else (None, in, false)
      }

    }
    ZTransducer[Any, Nothing, Byte, Chunk[Byte]] {
      ZRef
        .makeManaged[Chunk[Byte]](Chunk.empty)
        .map(stateRef => {
          case None =>
            var res: Chunk[Chunk[Byte]] = Chunk.empty
            (for {
              leftOver <- stateRef.get
              pair     <- ZIO.effectTotal(produceChunk(leftOver))
              _        <- stateRef.set(pair._2)
              repeat   <- ZIO.effectTotal(pair._1.isDefined && !pair._2.isEmpty)
              _        <- ZIO.effectTotal { res = res :+ pair._1.getOrElse(Chunk.empty) }

            } yield (repeat)).repeatWhile(c => c == true) *> ZIO.succeed(res)

          case Some(in) =>
            var res: Chunk[Chunk[Byte]] = Chunk.empty
            var start                   = true
            (for {
              leftOver <- stateRef.get
              pair <- if (start == true) ZIO.effectTotal(produceChunk(leftOver ++ in))
                     else ZIO.effectTotal(produceChunk(leftOver))
              _      <- ZIO.effectTotal { start = false }
              _      <- stateRef.set(pair._2)
              repeat <- ZIO.effectTotal(pair._1.isDefined && !pair._2.isEmpty)
              _ <- ZIO.effectTotal {
                    pair._1 match {
                      case None =>
                        if (pair._3 == true) {
                          res = res :+ Chunk.empty //Chunk(Chunk.empty)) means end of stream and Chunk.empty means more data for transducer
                        } else res
                      case Some(value) => res = res :+ value
                    }

                  }
            } yield (repeat)).repeatWhile(c => c == true) *> ZIO.succeed { res }

        })
    }
  }



}

class HttpRouter[R <: Has[MyLogging.Service]](val appRoutes: List[HttpRoutes[R]]) {

  def this(rt: HttpRoutes[R]*) = {
    this(rt.toList)
  }

  //TODO enforce those !!!!
  //val HTTP_HEADER_SZ          = 8096 * 2
  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100 //104 MB

  
  def route(c: Channel): ZIO[ZEnv with R, Exception, Unit] =
    route_do(c).forever.catchAll(ex => {
      ex match {
        case _: UpgradeRequest =>
          MyLogging.debug("console", "New websocket request spawned") *> IO.unit

        case _: java.nio.channels.InterruptedByTimeoutException =>
          MyLogging.debug("console", "Connection closed") *> IO.unit

        case _: BadInboundDataError =>
          MyLogging.debug("console", "Bad request, connection closed") *>
            ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad request.", true) *> IO.unit

        case _: HTTPHeaderTooBig =>
          MyLogging.error("console", "HTTP header exceeds allowed limit") *>
            ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad HTTP header.", true) *> IO.unit

        case e: java.io.FileNotFoundException  =>
          MyLogging.error("console", "File not found " + e.toString()) *>
            ResponseWriters.writeNoBodyResponse(c, StatusCode.NotFound, "Not found.", true) *> IO.unit

         case e: java.nio.file.NoSuchFileException  =>
          MyLogging.error("console", "File not found " + e.toString()) *>
            ResponseWriters.writeNoBodyResponse(c, StatusCode.NotFound, "Not found.", true) *> IO.unit   

        case _: AccessDenied =>
          MyLogging.error("console", "Access denied") *>
            ResponseWriters.writeNoBodyResponse(c, StatusCode.Forbidden, "Access denied.", true) *> IO.unit

        case _: scala.MatchError =>
          MyLogging.error("console", "Bad request(1)") *>
            ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad request (1).", true) *> IO.unit

        case e: TLSChannelError =>
          MyLogging.debug("console", "Remote peer closed connection") *> IO.unit

        case e: java.io.IOException =>
          MyLogging.error("console", "Remote peer closed connection (1) " + e.toString() + " " + e.getMessage()) *> IO.unit

        case e: ChunkedEncodingError =>
          MyLogging.error("console", e.toString()) *>
            ResponseWriters.writeNoBodyResponse(c, StatusCode.NotImplemented, "", true) *> IO.unit

        case e: Exception =>
          MyLogging.error("console", e.toString()) *>
            ResponseWriters.writeNoBodyResponse(c, StatusCode.InternalServerError, "", true) *> IO.unit
      }
    })

  private def route_do(c: Channel): ZIO[ZEnv with R, Exception, Unit] = {

    val header_pair = raw"(.{2,100}):\s+(.+)".r
    val http_line   = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r

    val rd_stream = ZStream.repeatEffect(Channel.read(c)).flatMap(ZStream.fromChunk(_))
    val r = rd_stream.peel(ZSink.fold(Chunk[Byte]()) { c =>
      !c.endsWith("\r\n\r\n")
    }((z, i: Byte) => z :+ i))
    for {
      _ <- r.use[ZEnv with R, Exception, Unit] {
            case (header_bytes, body_stream) =>
              val strings =
                ZStream.fromChunk(header_bytes).aggregate(ZTransducer.usASCIIDecode >>> ZTransducer.splitLines)

              val hdrs = strings.fold(Headers())((hdrs, line) => {
                line match {
                  case http_line(method, path, prot) =>
                    hdrs ++ Headers(HttpRouter._METHOD -> method, HttpRouter._PATH -> path, HttpRouter._PROTO -> prot)
                  case header_pair(attr, value) => hdrs + (attr.toLowerCase -> value)
                  case _                        => hdrs
                }
              })

              val T = for {
                h <- hdrs
                //_ <- ZIO( println( h.printHeaders ) )
                isChunked <- ZIO.effectTotal(h.getMval("transfer-encoding").exists(_.equalsIgnoreCase("chunked")))
                isContinue <- ZIO.effectTotal( h.get( "Expect").getOrElse("").equalsIgnoreCase( "100-continue" ) )
                _          <- ResponseWriters.writeNoBodyResponse( c, StatusCode.Continue, "", false  ).when( isChunked && isContinue )

                validate <- ZIO.effectTotal(
                             h.get(HttpRouter._METHOD)
                               .flatMap(_ => h.get(HttpRouter._PATH).flatMap(_ => h.get(HttpRouter._PROTO)))
                           )
                _ <- if (validate.isDefined) ZIO.unit else IO.fail(new BadInboundDataError())

                contentLen  <- ZIO.effectTotal(h.get("content-length").getOrElse("0"))
                contentLenL <- ZIO.fromTry(scala.util.Try(contentLen.toLong)).refineToOrDie[Exception]

                //validate content-len
                //contentLenL <- ZIO.fromTry(Try(contentLen.toLong))
                _ <- if (contentLenL > MAX_ALLOWED_CONTENT_LEN) ZIO.fail(new ContentLenTooBig) else ZIO.unit

                stream <- if (isChunked)
                           ZIO.effect(
                             body_stream
                               .aggregate( HttpRouter.chunkedDecode)
                               .collectWhile(new PartialFunction[Chunk[Byte], Chunk[Byte]] {
                                 def apply(v1: Chunk[Byte]): Chunk[Byte]  = v1
                                 def isDefinedAt(x: Chunk[Byte]): Boolean = !x.isEmpty
                               })
                           )
                         else
                           ZIO.effect(
                             body_stream
                               .take(contentLenL).mapChunks( c => Chunk.single( c ))
                               //.grouped(Int.MaxValue) //this will be configurable
                           )

                req <- ZIO.effect(Request(h, stream, c)).refineToOrDie[Exception]

                (response, post_proc) <- route_go(req, appRoutes).catchAll {
                                          case None =>
                                            IO.succeed(
                                              (Response.Error(StatusCode.NotFound), HttpRoutes.defaultPostProc)
                                            )
                                          case Some(e) => IO.fail(e)
                                        }
                response2 <- if (response.raw_stream == false) IO.effectTotal(post_proc(response))
                            else ZIO.succeed(response)

                _ <- response_processor(c, req, response2).catchAll { e =>
                      ZIO.fail(e)
                    }

              } yield (response)

              T.unit.refineToOrDie[Exception]

          }

    } yield ()

  }

  private def route_go(
    req: Request,
    appRoutes: List[HttpRoutes[R]]
  ): ZIO[ZEnv with R, Option[Exception], (Response, HttpRoutes.PostProc)] =
    appRoutes match {
      case h :: tail => {
        for {
          response <- h.run(req).map(r => (r, h.postProc)).catchAll {
                       case None => {
                         route_go(req, tail) //None - we need another one
                       }
                       case Some(e) =>
                         ZIO.fail(Some(e))
                     }
        } yield (response)
      }
      case Nil => ZIO.fail(None)
    }

  private def response_processor[A](
    ch: Channel,
    req: Request,
    resp: Response
  ): ZIO[ZEnv with R, Exception, Int] =
    if (resp.raw_stream == true) {
      resp.stream.foreach(chunk => { Channel.write(ch, chunk) }).refineToOrDie[Exception] *>
        ZIO.succeed(0)
    } else {

      val contType = ContentType(resp.headers.get("content-type").getOrElse(""))
      val chunked  = resp.isChunked
      val status   = resp.code

      if (resp.isChunked) {
        Logs.log_access(req, status, 0, "chunked" ).refineToOrDie[Exception] *>
          ResponseWriters.writeFullResponseFromStream(ch, resp).refineToOrDie[Exception].map(_ => 0)
      } else
        (for {
          body <- resp.stream.flatMap( c => { ZStream.fromChunk(c) }).runCollect
          res <- status match {
                  case StatusCode.OK =>
                    ResponseWriters.writeFullResponseBytes(ch, resp, resp.code, body, false)
                  case StatusCode.SeeOther => ResponseWriters.writeResponseRedirect(ch, new String(body.toArray))
                  case StatusCode.MethodNotAllowed =>
                    ResponseWriters.writeResponseMethodNotAllowed(ch, new String(body.toArray))
                  case StatusCode.NotFound =>
                    ResponseWriters.writeNoBodyResponse(ch, status, new String(body.toArray), true)
                  case StatusCode.UnsupportedMediaType =>
                    ResponseWriters.writeNoBodyResponse(ch, StatusCode.UnsupportedMediaType, "", true)
                  case _ => ResponseWriters.writeNoBodyResponse(ch, status, new String(body.toArray), true)
                }
          _ <- Logs.log_access(req, status, body.size )

        } yield (0)).refineToOrDie[Exception]

    }

}
