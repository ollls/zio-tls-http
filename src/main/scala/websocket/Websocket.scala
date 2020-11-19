package zhttp

import zio.{ Chunk, ZEnv, ZIO }

import java.security.MessageDigest
import java.util.Base64
import MyLogging.MyLogging
import java.nio.ByteBuffer

object Websocket {

  private val magicString =
    "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes("US-ASCII")

  def apply(isClient: Boolean = false) = new Websocket(isClient)

}

class Websocket(isClient: Boolean) {

  private val IN_J_BUFFER = java.nio.ByteBuffer.allocate(0xffff * 2) //64KB * 2
  private val frames      = new FrameTranscoder(isClient)

  def closeReply(req: Request) = {
    val T = frames.frameToBuffer(WebSocketFrame.Close())
    req.ch.write(Chunk.fromArray(T(0).array()) ++ Chunk.fromArray(T(1).array()))
  }

  def pongReply(req: Request) = {
    val T = frames.frameToBuffer(WebSocketFrame.Pong())
    req.ch.write(Chunk.fromArray(T(0).array()))
  }

  def pingReply(req: Request) = {
    val T = frames.frameToBuffer(WebSocketFrame.Ping())
    req.ch.write(Chunk.fromArray(T(0).array()))
  }

  def writeBinary(req: Request, data: Chunk[Byte], last: Boolean = true) = {
    val frame = WebSocketFrame.Binary(data, last)
    writeFrame(req, frame)
  }

  def writeText(req: Request, str: String, last: Boolean = true) = {
    val frame = WebSocketFrame.Text(str, last)
    writeFrame(req, frame)
  }

  /////////////////////////////////////////////////////////////////////
  private def genWsResponse(resp: Response): String = {
    val r = new StringBuilder
    r ++= "HTTP/1.1 101 Switching Protocols\n"
    resp.headers.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + "\n" }
    r ++= "\n"
    val T = r.toString()
    T
  }

  private def genAcceptKey(str: String): String = {
    val crypt = MessageDigest.getInstance("SHA-1")
    crypt.reset()
    crypt.update(str.getBytes("US-ASCII"))
    crypt.update(Websocket.magicString)
    val bytes = crypt.digest()
    Base64.getEncoder.encodeToString(bytes)
  }

  //Not used, websocket client support not implemented yet
  def startClientHadshake(host: String) = {
    val key = {
      val bytes = new Array[Byte](16)
      scala.util.Random.nextBytes(bytes)
      Base64.getEncoder.encodeToString(bytes)
    }

    Response.Ok
      .hdr("Host" -> host)
      .hdr("Upgrade" -> "websocket")
      .hdr("Connection" -> "Upgrade")
      .hdr("Sec-WebSocket-Version" -> "13")
      .hdr("Sec-WebSocket-Key" -> key)

  }

  private def serverHandshake(req: Request) = {

    val result = for {
      uval <- req.headers.get("Upgrade")
      cval <- req.headers.get("Connection")
      aval <- req.headers.get("Sec-WebSocket-Version")
      kval <- req.headers.get("Sec-WebSocket-Key")
      _ <- Option(
            uval.equalsIgnoreCase("websocket") &&
              cval.equalsIgnoreCase("Upgrade") &&
              aval.equalsIgnoreCase("13") &&
              Base64.getDecoder.decode(kval).length == 16
          ).collect { case true => true } //convert false to None
      rspKey <- Some(genAcceptKey(kval))

    } yield (rspKey)

    val zresp = result.map(key => {
      Response.Ok.hdr("Upgrade" -> "websocket").hdr("Connection" -> "Upgrade").hdr("Sec-WebSocket-Accept" -> key)
    }) match {
      case None =>
        Left(
          new Exception(
            "Invalid websocket upgrade request: " +
              "websocket headers or version is invalid"
          )
        )
      case Some(v) => Right(v)
    }

    zresp
  }

  def writeFrame(req: Request, frame: WebSocketFrame) = {
    def processArray(ab: Array[ByteBuffer], i: Int): ZIO[ZEnv, Exception, Int] =
      if (i < ab.length)
        req.ch.write(Chunk.fromArray(ab(i).array)) *> processArray(ab, i + 1)
      else ZIO.succeed(0)

    for {
      array <- ZIO.effect(frames.frameToBuffer(frame))
      _     <- processArray(array, 0)

    } yield ()
  }

  def readFrame(req: Request): ZIO[ZEnv, Exception, WebSocketFrame] = {

    val T = for {
      _     <- req.ch.readBuffer(IN_J_BUFFER)
      frame <- ZIO.effect(frames.bufferToFrame(IN_J_BUFFER))
      _ <- if (frame.opcode == WebSocketFrame.PING) pongReply(req)
          else ZIO.unit
    } yield (frame)

    (T.repeatWhile(_.opcode == WebSocketFrame.PING)).refineToOrDie[Exception]

  }

  //function only process close requests and supports multiframe exchnage
  //frame type inconsitencies such as ( unexpected cont, binary vs text) should be solved inside of io_func
  def process_io(req: Request, io_func: WebSocketFrame => ZIO[ZEnv, Throwable, WebSocketFrame]) = {
    val T = for {
      frame <- readFrame(req)

      frame_out <- if (frame.opcode != WebSocketFrame.CLOSE)
                    io_func(frame)
                  else
                    ZIO.succeed(WebSocketFrame.Close())
      //many outbound frames, call func sequentialy, to grab all of them
      _ <- if (frame_out.last == false)
            io_func(frame).repeatWhile(frame => frame.last)
          else ZIO.unit
      //we rotate thru continuation frames, not sending response,
      //until we get last packet.
      _ <- if (frame.opcode == WebSocketFrame.CONTINUATION && frame.last == false) ZIO.unit
          else writeFrame(req, frame_out)

    } yield (frame)

    //many inbound frames
    (T.repeatWhile(frame => !frame.last || frame.opcode == WebSocketFrame.CLOSE)).map(_ => NoResponse)

  }

  def accept(req: Request): ZIO[ZEnv with MyLogging, Exception, Unit] = {
    req.ch.keepAlive(0)
    val T = for {
      res <- ZIO.effect(serverHandshake(req))
      _ <- res match {
            case Right(response) =>
              req.ch.remoteAddress.flatMap(
                adr =>
                  MyLogging.trace(
                    "console",
                    "Webocket request initiated from: " + adr.get.toInetSocketAddress.address.canonicalHostName
                  )
              ) *> req.ch.write(Chunk.fromArray(genWsResponse(response).getBytes()))
            case Left(exception) => ZIO.fail(exception)
          }

    } yield ()

    T.refineToOrDie[Exception]
  }

  def acceptAndRead(req: Request): ZIO[ZEnv with MyLogging, Exception, WebSocketFrame] = {

    req.ch.keepAlive(0)

    val T = for {
      res <- ZIO.effect(serverHandshake(req))
      _ <- res match {
            case Right(response) =>
              req.ch.remoteAddress.flatMap(
                adr =>
                  MyLogging.trace(
                    "console",
                    "Webocket request initiated from: " + adr.get.toInetSocketAddress.address.canonicalHostName
                  )
              ) *> req.ch.write(Chunk.fromArray(genWsResponse(response).getBytes()))
            case Left(exception) => ZIO.fail(exception)
          }
      frame <- readFrame(req)

    } yield (frame)

    T.refineToOrDie[Exception]

  }

}
