package zhttp

import zio.{Chunk, IO, ZIO}

import zhttp.netio._
import zio.stream.ZStream
import zio.stream.ZPipeline
import zio.stream.ZChannel
import zio.stream.ZSink
import java.nio.ByteBuffer
import zio.logging.backend.SLF4J

sealed trait HTTPError                              extends Exception
sealed case class BadInboundDataError()             extends HTTPError
sealed case class HTTPHeaderTooBig()                extends HTTPError
sealed case class AccessDenied()                    extends HTTPError
sealed case class ContentLenTooBig()                extends HTTPError
sealed case class UpgradeRequest()                  extends HTTPError
sealed case class MediaEncodingError(msg: String)   extends Exception(msg)
sealed case class ChunkedEncodingError(msg: String) extends Exception(msg)
sealed case class WebSocketClosed(msg: String)      extends Exception(msg)

object HttpRouter {
  val _METHOD = "%%%http_method%%%"
  val _PATH   = "%%%http_path%%%"
  val _PROTO  = "%%%http_protocol%%%"

  def parseTrail(in: Chunk[Byte], pos: Int): Boolean = {
    if (in.size - pos < 4) return false // more data
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

      if (found == false) return false // more data

      // we will ignore trailing block here, but it can be provided, right after empty block for further use.
      // TODO
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

    // extract size
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
        else (None, in, false) // more data
      } else (None, in, false)
    }

  }

  /*+:class ZPipeline[-Env, +Err, -In, +Out](val channel: ZChannel[Env, Nothing, Chunk[In], Any, Err, Chunk[Out], Any])
   */

  def chunkedDecode: zio.stream.ZPipeline[Any, Exception, Byte, Chunk[Byte]] = {

    def chunk_converter(leftOver: Chunk[Byte]): ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Chunk[Byte]], Any] = {
      val converter: ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Chunk[Byte]], Any] = ZChannel.readWith(
        (in: Chunk[Byte]) => {

          val res = produceChunk(leftOver ++ in)

          res match {
            case (Some(chunk), leftover, false) => ZChannel.write(Chunk.single(chunk)) *> chunk_converter(leftover)
            case (None, leftover, false)        => chunk_converter(leftover)            // no chunk yet but data coming
            case (None, leftover, true)         => ZChannel.succeed(true)
            case _                              => ZChannel.fail(new Exception("TEMP")) // TODO

          }
        },
        (err: Exception) => ZChannel.fail(err),
        (done: Any) => ZChannel.succeed(true)
      )
      converter
    }

    ZPipeline.fromChannel(chunk_converter(Chunk.empty))

  }

  /** **************************************************************
    */
  // So the last chunk and 2 trailing headers might look like this:
  // 0<CRLF>
  // Date:Sun, 06 Nov 1994 08:49:37 GMT<CRLF>
  // Content-MD5:1B2M2Y8AsgTpgAmY7PhCfg==<CRLF>
  // <CRLF>
  /*
  def chunkedDecode: ZPipeline[Any, Nothing, Byte, Chunk[Byte]] = {

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



    ZPipeline[Any, Nothing, Byte, Chunk[Byte]] {
      ZRef
        .makeManaged[Chunk[Byte]](Chunk.empty)
        .map(stateRef => {
          case None =>
            var res: Chunk[Chunk[Byte]] = Chunk.empty
            (for {
              leftOver <- stateRef.get
              pair     <- ZIO.succeed(produceChunk(leftOver))
              _        <- stateRef.set(pair._2)
              repeat   <- ZIO.succeed(pair._1.isDefined && !pair._2.isEmpty)
              _        <- ZIO.succeed { res = res :+ pair._1.getOrElse(Chunk.empty) }

            } yield (repeat)).repeatWhile(c => c == true) *> ZIO.succeed(res)

          case Some(in) =>
            var res: Chunk[Chunk[Byte]] = Chunk.empty
            var start                   = true
            (for {
              leftOver <- stateRef.get
              pair <- if (start == true) ZIO.succeed(produceChunk(leftOver ++ in))
                     else ZIO.succeed(produceChunk(leftOver))
              _      <- ZIO.succeed { start = false }
              _      <- stateRef.set(pair._2)
              repeat <- ZIO.succeed(pair._1.isDefined && !pair._2.isEmpty)
              _ <- ZIO.succeed {
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
  } */

}

class HttpRouter[Env](val appRoutes: List[HttpRoutes[Env]]) {

  def this(rt: HttpRoutes[Env]*) = this(rt.toList)

  // TODO enforce those !!!!
  // val HTTP_HEADER_SZ          = 8096 * 2
  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100 // 104 MB

  def route(c: IOChannel)(leftOverData: Chunk[Byte]): ZIO[Env, Throwable, Unit] =
    zio.Ref.make(true).flatMap { ref =>
      route_do(c, leftOverData, ref).forever.catchAll(ex =>
        ex match {

          case _: java.nio.channels.InterruptedByTimeoutException =>
            ZIO.logDebug( "Connection closed") *> ZIO.unit

          case _: BadInboundDataError =>
            ZIO.logDebug("Bad request, connection closed") *>
              ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad request.", true) *> ZIO.unit

          case _: HTTPHeaderTooBig =>
            ZIO.logError("HTTP header exceeds allowed limit") *>
              ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad HTTP header.", true) *> ZIO.unit

          case e: java.io.FileNotFoundException =>
            ZIO.logError("File not found " + e.toString()) *>
              ResponseWriters.writeNoBodyResponse(c, StatusCode.NotFound, "Not found.", true) *> ZIO.unit

          case e: java.nio.file.NoSuchFileException =>
            ZIO.logError("File not found " + e.toString()) *>
              ResponseWriters.writeNoBodyResponse(c, StatusCode.NotFound, "Not found.", true) *> ZIO.unit

          case _: AccessDenied =>
            ZIO.logError("Access denied") *>
              ResponseWriters.writeNoBodyResponse(c, StatusCode.Forbidden, "Access denied.", true) *> ZIO.unit

          case _: scala.MatchError =>
            ZIO.logError("Bad request(1)") *>
              ResponseWriters.writeNoBodyResponse(c, StatusCode.BadRequest, "Bad request (1).", true) *> ZIO.unit

          case e: TLSChannelError =>
            ZIO.logDebug("Remote peer closed connection") *> ZIO.unit

          case e: java.io.IOException =>
            ZIO.logError( "Remote peer closed connection (1) " + e.getMessage()) *> ZIO.unit

          case e: ChunkedEncodingError =>
            ZIO.logError(e.toString()) *>
              ResponseWriters.writeNoBodyResponse(c, StatusCode.NotImplemented, "", true) *> ZIO.unit

          case e: WebSocketClosed => ZIO.logDebug("Websocket closed: " + e.getMessage()) *> ZIO.unit

          case e: Exception =>
            ZIO.logError(e.toString()) *>
              ResponseWriters.writeNoBodyResponse(c, StatusCode.InternalServerError, "", true) *> ZIO.unit
        }
      )
    }

  def readWithLeftOver(c: IOChannel, leftOverData: Chunk[Byte], refStart: zio.Ref[Boolean]) = {
    for {
      start      <- refStart.get
      readEffect <- c.read().when(start == false)
      res <- ZIO.succeed(readEffect match {
        case Some(e0) => e0
        case None     => leftOverData
      })
      _ <- refStart.set(false)
    } yield (res)
  }

  def route_do(c: IOChannel, leftOverData: Chunk[Byte], refStart: zio.Ref[Boolean]): ZIO[Env, Throwable, Unit] = {

    val header_pair = raw"(.{2,100}):\s+(.+)".r
    val http_line   = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r

    // val rd_stream: ZStream[R, Throwable, Byte] =
    // if (leftOverData.isEmpty == false) ZStream.fromChunk(leftOverData) ++ ZStream.repeatZIO(c.read()).flatMap(ZStream.fromChunk(_))
    // else ZStream.repeatZIO(c.read()).flatMap(ZStream.fromChunk(_))

    // var start = true;

    val rd_stream: ZStream[Any, Throwable, Byte] =
      ZStream.repeatZIO(readWithLeftOver(c, leftOverData, refStart)).flatMap(ZStream.fromChunk(_))

    val r = rd_stream.peel(ZSink.fold(Chunk.empty[Byte]) { c =>
      {
        // println( "chunk again")
        !c.endsWith("\r\n\r\n")
      }
    }((z, i: Byte) => z :+ i))

    for {
      _ <- ZIO.scoped {
        r.flatMap { case (header_bytes, body_stream) =>
          val strings =
            ZStream.fromChunk(header_bytes).via(ZPipeline.usASCIIDecode >>> ZPipeline.splitLines)

          val hdrs: ZIO[Env, Exception, Headers] = strings.runFold[Headers](Headers())((hdrs, line) => {
            line match {
              case http_line(method, path, prot) =>
                hdrs ++ Headers(HttpRouter._METHOD -> method, HttpRouter._PATH -> path, HttpRouter._PROTO -> prot)
              case header_pair(attr, value) => hdrs + (attr.toLowerCase -> value)
              case _                        => hdrs
            }
          })

          val T = for {
            h: Headers <- hdrs
            // _ <- ZIO( println( h.printHeaders ) )

            isWebSocket <- ZIO.succeed(
              h.get("Upgrade")
                .map(_.equalsIgnoreCase("websocket"))
                .collect { case true => true }
                .getOrElse(false)
            )

            isChunked <- ZIO.succeed(h.getMval("transfer-encoding").exists(_.equalsIgnoreCase("chunked")))

            isContinue <- ZIO.succeed(h.get("Expect").getOrElse("").equalsIgnoreCase("100-continue"))
            _ <- ResponseWriters
              .writeNoBodyResponse(c, StatusCode.Continue, "", false)
              .when(isChunked && isContinue)

            validate <- ZIO.succeed(
              h.get(HttpRouter._METHOD)
                .flatMap(_ => h.get(HttpRouter._PATH).flatMap(_ => h.get(HttpRouter._PROTO)))
            )
            _ <- if (validate.isDefined) ZIO.unit else ZIO.fail(new BadInboundDataError())

            contentLen  <- ZIO.succeed(h.get("content-length").getOrElse("0"))
            contentLenL <- ZIO.fromTry(scala.util.Try(contentLen.toLong)).refineToOrDie[Exception]

            // validate content-len
            // contentLenL <- ZIO.fromTry(Try(contentLen.toLong))
            _ <- if (contentLenL > MAX_ALLOWED_CONTENT_LEN) ZIO.fail(new ContentLenTooBig) else ZIO.unit

            stream <-
              if (isWebSocket) ZIO.attempt(body_stream.mapChunks(c => Chunk.single(c)))
              else if (isChunked)
                ZIO.attempt(
                  body_stream.via(HttpRouter.chunkedDecode)
                  /*
                               .collectWhile(new PartialFunction[Chunk[Byte], Chunk[Byte]] {
                                 def apply(v1: Chunk[Byte]): Chunk[Byte]  = v1
                                 def isDefinedAt(x: Chunk[Byte]): Boolean = !x.isEmpty
                               })*/
                )
              else
                ZIO.attempt(
                  body_stream
                    .take(contentLenL)
                    .mapChunks(c => Chunk.single(c))
                  // .grouped(Int.MaxValue) //this will be configurable
                )

            req <- ZIO.attempt(Request(h, stream, c)).refineToOrDie[Exception]
            response_t <- route_go(req, appRoutes).catchAll {
              case None =>
                ZIO.succeed(
                  (Response.Error(StatusCode.NotFound), HttpRoutes.defaultPostProc)
                )
              case Some(e) => ZIO.fail(e)
            }
            response  = response_t._1
            post_proc = response_t._2

            response2 <-
              if (response.raw_stream == false && req.isWebSocket == false)
                ZIO.succeed(post_proc(response))
              else ZIO.succeed(response_t._1)

            _ <- response_processor(c, req, response2).catchAll { e =>
              ZIO.fail(e)
            }

          } yield (response_t._1)

          T.unit.refineToOrDie[Exception]

        }
      } /* use or scope */

    } yield ()

  }

  private def route_go(
      req: Request,
      appRoutes: List[HttpRoutes[Env]]
  ): ZIO[Env, Option[Exception], (Response, HttpRoutes.PostProc)] =
    appRoutes match {
      case h :: tail => {
        for {
          response <- h.run(req).map(r => (r, h.postProc)).catchAll {
            case None => {
              route_go(req, tail) // None - we need another one
            }
            case Some(e) =>
              ZIO.fail(Some(e))
          }
        } yield (response)
      }
      case Nil => ZIO.fail(None)
    }

  private def response_processor[A](
      ch: IOChannel,
      req: Request,
      resp: Response
  ): ZIO[Env, Throwable, Int] =
    if (req.isWebSocket && resp.code.isSuccess) {
      req.ch.remoteAddress().map(c => c.asInstanceOf[java.net.InetSocketAddress].getHostString()).flatMap(hostName => ZIO.fail(WebSocketClosed(hostName)))
    } else if (resp == NoResponse) {
      ZIO.succeed(0)
    } else if (resp.raw_stream == true) {
      val stream = resp.streamWith[Env]
      stream.foreach(chunk => ch.write(ByteBuffer.wrap(chunk.toArray))).refineToOrDie[Exception] *>
        ZIO.succeed(0)
    } else {

      val contType = ContentType(resp.headers.get("content-type").getOrElse(""))
      val chunked  = resp.isChunked
      val status   = resp.code

      if (resp.isChunked) {
        //https://zio.dev/zio-logging/slf4j
        //access
         ZIO.logInfo( req.method.toString + " " + req.uri.toString() + "  " + status.value + " chunked" ) @@ SLF4J.loggerName( "access") *>
         ResponseWriters.writeFullResponseFromStream(ch, resp).refineToOrDie[Exception].map(_ => 0)
        //Logs.log_access(req, status, 0, "chunked").refineToOrDie[Exception] *>
          ResponseWriters.writeFullResponseFromStream(ch, resp).refineToOrDie[Exception].map(_ => 0)
      } else
        (for {
          body <- resp.streamWith[Env].flatMap(c => { ZStream.fromChunk(c) }).runCollect
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
          //_ <- Logs.log_access(req, status, body.size)
          _ <- ZIO.logInfo( req.method.toString + " " + req.uri.toString() + "  " + status.value + " " + body.size ) @@ SLF4J.loggerName( "access")

        } yield (0)).refineToOrDie[Exception]

    }

}
