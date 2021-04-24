package zhttp

import zio.{ Chunk, ZEnv, ZIO }

import java.security.MessageDigest
import java.util.Base64
import MyLogging.MyLogging
import java.nio.ByteBuffer
import zio.stream.ZStream

object Websocket {
 
  val WS_PACKET_SZ = 32768

  private val magicString =
    "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes("US-ASCII")
  def apply(isClient: Boolean = false) = new Websocket(isClient)


}

class Websocket(isClient: Boolean) {

  final val CRLF = "\r\n"

  private val IN_J_BUFFER = java.nio.ByteBuffer.allocate(0xffff * 2) //64KB * 2
  private val frames      = new FrameTranscoder(isClient)

  def closeReply(req: Request) = {
    val T = frames.frameToBuffer(WebSocketFrame.Close())
    Channel.write(req.ch, Chunk.fromArray(T(0).array()) ++ Chunk.fromArray(T(1).array()))
  }

  def pongReply(req: Request, data: Chunk[Byte] = Chunk.empty) = {
    val T = frames.frameToBuffer(WebSocketFrame.Pong(data))
    Channel.write(req.ch, Chunk.fromArray(T(0).array()))
  }

  def pingReply(req: Request, data: Chunk[Byte] = Chunk.empty) = {
    val T = frames.frameToBuffer(WebSocketFrame.Ping(data))
    Channel.write(req.ch, Chunk.fromArray(T(0).array()))
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
    r ++= "HTTP/1.1 101 Switching Protocols" + CRLF
    resp.headers.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + CRLF }
    r ++= CRLF
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
        Channel.write(req.ch, Chunk.fromArray(ab(i).array)) *> processArray(ab, i + 1)
      else ZIO.succeed(0)

    for {
      array <- ZIO.effect(frames.frameToBuffer(frame))
      _     <- processArray(array, 0)

    } yield ()
  }

  def readFrame(req: Request): ZIO[ZEnv, Exception, WebSocketFrame] = {

    val T = for {
      _     <- Channel.readBuffer(req.ch, IN_J_BUFFER)
      frame <- ZIO.effect(frames.bufferToFrame(IN_J_BUFFER))
      _ <- if (frame.opcode == WebSocketFrame.PING) pongReply(req)
          else ZIO.unit
    } yield (frame)

    (T.repeatWhile(_.opcode == WebSocketFrame.PING)).refineToOrDie[Exception]

  }

  def accept(req: Request): ZIO[ZEnv with MyLogging, Exception, Unit] = {
    Channel.keepAlive(req.ch, 0)
    val T = for {
      res <- ZIO.effect(serverHandshake(req))
      _ <- res match {
            case Right(response) =>
              Channel
                .remoteAddress(req.ch)
                .flatMap(
                  adr =>
                    MyLogging.debug(
                      "console",
                      "Webocket request initiated from: " + adr.get.toInetSocketAddress.address.canonicalHostName
                    )
                ) *> Channel.write(req.ch, Chunk.fromArray(genWsResponse(response).getBytes()))
            case Left(exception) => ZIO.fail(exception)
          }

    } yield ()

    T.refineToOrDie[Exception]
  }

  def acceptAndRead(req: Request): ZIO[ZEnv with MyLogging, Exception, WebSocketFrame] = {
    Channel.keepAlive(req.ch, 0)
    val T = for {
      res <- ZIO.effect(serverHandshake(req))
      _ <- res match {
            case Right(response) =>
              Channel
                .remoteAddress(req.ch)
                .flatMap(
                  adr =>
                    MyLogging.debug(
                      "console",
                      "Webocket request initiated from: " + adr.get.toInetSocketAddress.address.canonicalHostName
                    )
                ) *> Channel.write(req.ch, Chunk.fromArray(genWsResponse(response).getBytes()))
            case Left(exception) => ZIO.fail(exception)
          }
      frame <- readFrame(req)
    } yield (frame)
    T.refineToOrDie[Exception]
  }


  private def doPingPong(req: Request, f0: WebSocketFrame) =
    f0 match {
      case WebSocketFrame.Ping(data) => pongReply(req, data)
      case _                         => ZIO.succeed(0)
    }


  def receiveTextAsStream(req: Request) = {
    val stream = req.stream >>= (ZStream.fromChunk(_))
    val s0 = stream
      .aggregate(FrameTransducer.make)
      .tap(doPingPong(req, _))
      .filter(_.opcode != WebSocketFrame.PING)
      .takeUntil(_.opcode == WebSocketFrame.CLOSE)
      .filter(_.opcode != WebSocketFrame.CLOSE)

    s0
  }

  def receiveBinaryAsStream(req: Request) = {
    val stream = req.stream >>= (ZStream.fromChunk(_))
    val s0 = stream
      .aggregate(FrameTransducer.make)
      .tap(doPingPong(req, _))
      .filter(_.opcode != WebSocketFrame.PING)
      .takeUntil(_.opcode == WebSocketFrame.CLOSE)
      .filter(_.opcode != WebSocketFrame.CLOSE)
    s0
  }

 
  def sendAsTextStream(req: Request, stream: ZStream[Any, Nothing, String]) = {
    val stream0 = stream.grouped( Websocket.WS_PACKET_SZ )
    //here we need to use special empty continuation packed marked as last.
    val last   = ZStream("").map(c => WebSocketFrame.Continuation(Chunk.fromArray(c.getBytes()), true))
    val first  = stream.take(1).map(WebSocketFrame.Text(_, false))
    val middle = stream.drop(1).map(c => WebSocketFrame.Continuation(Chunk.fromArray(c.getBytes()), false))

    val result: ZStream[Any, Nothing, WebSocketFrame] = first ++ middle ++ last
    result.foreach(frame => writeFrame(req, frame).orDie)
  }

  def sendAsBinaryStream(req: Request, stream: ZStream[Any, Nothing, Chunk[Byte]]) = {
     val stream0 = stream.grouped( Websocket.WS_PACKET_SZ )
    val last   = ZStream(WebSocketFrame.Continuation(Chunk[Byte](), true))
    val first  = stream.take(1).map(WebSocketFrame.Binary(_, false))
    val middle = stream.drop(1).map(c => WebSocketFrame.Continuation(c, false))

    val result: ZStream[Any, Nothing, WebSocketFrame] = first ++ middle ++ last
    result.foreach(frame => writeFrame(req, frame).orDie)
  }

}
