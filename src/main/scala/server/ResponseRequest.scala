package zhttp

import zio.Chunk
import java.net.URI
import zio.json._
import zio.stream.ZStream
import zio.ZEnv
import zio.ZIO

sealed case class Request(headers: Headers, stream: ZStream[ZEnv, Exception, Chunk[Byte]], ch: Channel) {

  def path: String             = headers.get(HttpRouter._PATH).getOrElse("")
  def method: Method           = Method(headers.get(HttpRouter._METHOD).getOrElse(""))
  def contentLen: String       = headers.get("content-length").getOrElse("0") //keep it string
  def uri: URI                 = new URI(path)
  def contentType: ContentType = ContentType(headers.get("content-type").getOrElse(""))
  def isJSONBody: Boolean      = contentType == ContentType.JSON

  def isWebSocket: Boolean =
    headers
      .get("Upgrade")
      .map(_.equalsIgnoreCase("websocket"))
      .collect { case true => true }
      .getOrElse(false)

  def transferEncoding = headers.getMval("transfer-encoding")

  def isChunked = transferEncoding.exists(_.equalsIgnoreCase("chunked"))

  def body = stream.runCollect.map(_.flatten): ZIO[ZEnv, Exception, Chunk[Byte]]

  //def chunkedBody( chunkSize : Int ) =  stream.aggregate( ZTransducer.collectAllN(chunkSize ) )

  def fromJSONToStream[A: JsonDecoder] = stream.map(chunk => new String(chunk.toArray).fromJson[A])

  def fromJSON[A: JsonDecoder] =
    for {
      b <- stream.runCollect
      a <- ZIO
            .absolve(ZIO.effect(new String(b(0).toArray).fromJson[A]))
            .mapError(str => new MediaEncodingError(s"JSON schema error: $str"))
    } yield (a)

  def bodyAsText: ZIO[ZEnv, Throwable, String] =
    for {
      b <- body
      a <- ZIO.effect(new String(b.toArray))
    } yield (a)

}

object Response {

  def Ok(): Response = new Response(StatusCode.OK, Headers())

  def raw_stream(str: ZStream[ZEnv, Throwable, Chunk[Byte]]) = new Response(StatusCode.OK, Headers(), str, true)

  def Error(code: StatusCode): Response = new Response(code, Headers())
}

object NoResponse extends Response(StatusCode.NotImplemented, null)

//Response ///////////////////////////////////////////////////////////////////////////
sealed case class Response(
  code: StatusCode,
  headers: Headers,
  body: ZStream[ZEnv, Throwable, Chunk[Byte]] = ZStream.empty,
  raw_stream: Boolean = false
) {

  def hdr(hdr: Headers): Response = new Response(this.code, this.headers ++ hdr, this.body)

  def hdr(pair: (String, String)) = new Response(this.code, this.headers + pair, this.body)

  def cookie(cookie: Cookie) = {
    val pair = ("Set-Cookie" -> cookie.toString())
    new Response(this.code, this.headers + pair, this.body)
  }

  def asStream( s0 : ZStream[ZEnv, Throwable, Chunk[Byte]]) = new Response(this.code, this.headers, s0)

  def asTextBody(text: String): Response = {
    val s0 = ZStream(Chunk.fromArray(text.getBytes))
    new Response(this.code, this.headers, s0).contentType(ContentType.Plain)
  }

  def asJsonBody[B: JsonEncoder](obj: B): Response = {
    val s0 = ZStream(Chunk.fromArray(obj.toJson.getBytes))
    new Response(this.code, this.headers, s0).contentType(ContentType.JSON)
  }

  def asJsonStream[B: JsonEncoder](objs: Chunk[B]) = {
    val s0 = ZStream.fromChunk(objs).map(obj => Chunk.fromArray(obj.toJson.getBytes()))
    new Response(this.code, this.headers, s0).contentType(ContentType.JSON)
  }

  def contentType(type0: ContentType) =
    new Response(this.code, this.headers + ("content-type" -> type0.toString()), this.body)

  def isChunked = transferEncoding.exists(_.equalsIgnoreCase("chunked"))

  def transferEncoding = headers.getMval("transfer-encoding")

  def transferEncoding(vals0: String*) =
    new Response(this.code, vals0.foldLeft(this.headers)((h, v) => h + ("transfer-encoding" -> v)), this.body)

}
