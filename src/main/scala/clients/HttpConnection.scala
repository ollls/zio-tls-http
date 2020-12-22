package zhttp.clients

import zio.ZIO
import zio.ZEnv
import zio.blocking.Blocking
import zio.Chunk
import zio.blocking.effectBlocking
import zio.json._
import scala.io.Source
import scala.util.Try

import zhttp.{ Channel, TlsChannel }
import zhttp.Method
import java.net.URI
import javax.net.ssl.SSLContext
import nio.SocketAddress
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import nio.channels.AsynchronousTlsByteChannel
import nio.channels.AsynchronousSocketChannel
import java.security.KeyStore
import zhttp.Headers
import zhttp.ContentType
import zhttp.Cookie
import zhttp.MyLogging
import zhttp.MyLogging.MyLogging

sealed case class HttpConnectionError(msg: String)     extends Exception(msg)
sealed case class HttpResponseHeaderError(msg: String) extends Exception(msg)

case class ClientResponse(val hdrs: Headers, val code: String, body: Chunk[Byte]) {
  def protocol    = hdrs.get("%prot").getOrElse("")
  def httpString  = code.toString + " " + hdrs.get("%message").getOrElse("")
  def isKeepAlive = hdrs.get("Connection").getOrElse("").equalsIgnoreCase("keep-alive")

  def asText: String = new String(body.toArray)

  def asObjfromJSON[A: JsonDecoder]: A =
    new String(body.toArray).fromJson[A] match {
      case Right(v) => v
      case Left(v)  => throw new HttpConnectionError(s"JSON schema error: $v")
    }
}

case class ClientRequest(
  val method: Method,
  val path: String,
  val hdrs: Headers = Headers(),
  val body: Option[Chunk[Byte]] = None
) {

  def body(data: Chunk[Byte]): ClientRequest = ClientRequest(this.method, this.path, this.hdrs, Some(data))

  def asJsonBody[B: JsonEncoder](body0: B): ClientRequest = {
    val json = body0.toJson.getBytes
    body(Chunk.fromArray(json)).contentType(ContentType.JSON)
  }

  def asTextBody(body0: String): ClientRequest =
    body(Chunk.fromArray(body0.getBytes)).contentType(ContentType.Plain)

  def hdr(hdr: Headers): ClientRequest = new ClientRequest(this.method, this.path, hdrs ++ hdr, this.body)

  def hdr(pair: (String, String)) = new ClientRequest(this.method, this.path, hdrs + pair, this.body)

  def contentType(type0: ContentType) =
    new ClientRequest(this.method, this.path, hdrs + ("content-type" -> type0.toString()), this.body)

  def cookie(cookie: Cookie) = {
    val pair = ("Set-Cookie" -> cookie.toString())
    new ClientRequest(this.method, this.path, hdrs + pair, this.body)
  }

}

//Request to Request, enriched with headers
case class FilterProc(run: ClientRequest => ClientRequest )

object HttpConnection {

  val HTTP_HEADER_SZ          = 8096 * 2
  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100
  val TLS_PROTOCOL_TAG        = "TLSv1.2"
  val CLIENT_TAG              = "zio-tls-http"

  private def buildSSLContext(protocol: String, JKSkeystore: String, password: String) = {
    val sslContext: SSLContext = SSLContext.getInstance(protocol)

    val keyStore: KeyStore = KeyStore.getInstance("JKS")

    val ks = new java.io.FileInputStream(JKSkeystore)

    if (ks == null) ZIO.fail(new java.io.FileNotFoundException(JKSkeystore + " keystore file not found."))

    keyStore.load(ks, password.toCharArray())

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    tmf.init(keyStore)

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, password.toCharArray())
    sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

    sslContext
  }

  private def buildSSLContextM(
    protocol: String,
    jKSkeystore: String,
    password: String
  ) = effectBlocking(buildSSLContext(protocol, jKSkeystore, password)).refineToOrDie[Exception]

  private def connectSSL(
    host: String,
    port: Int,
    trustKeystore: String = null,
    password: String = ""
  ): ZIO[zio.ZEnv, Exception, Channel] = {
    val T = for {
      address <- SocketAddress.inetSocketAddress(host, port)
      ssl_ctx <- if (trustKeystore == null) effectBlocking(SSLContext.getDefault()).refineToOrDie[Exception]
                else buildSSLContextM(TLS_PROTOCOL_TAG, trustKeystore, password)
      ch     <- AsynchronousSocketChannel()
      _      <- ch.connect(address).mapError(e => HttpConnectionError(e.toString))
      tls_ch <- AsynchronousTlsByteChannel(ch, ssl_ctx)
    } yield (tls_ch)

    T.map(c => new TlsChannel(c))
  }

  def connect( url: String, trustKeystore: String = null, password: String = "" ) 
      = connectWithFilter( url, req => req, trustKeystore, password )

  def connectWithFilter(url: String, filter: ClientRequest => ClientRequest,
              trustKeystore: String = null, password: String = "") = {
    val u    = new URI(url)
    val port = if (u.getPort == -1) 443 else u.getPort
    (if (u.getScheme().equalsIgnoreCase("https")) {
       val ss = connectSSL(u.getHost(), port, trustKeystore, password).map(new HttpConnection(u, _, FilterProc( filter ) ))
       ss
     } else {
       throw new Exception("HttpConnection: Unsupported scheme - " + u.getScheme())
     }).catchAll(e => ZIO.fail(new HttpConnectionError(url + " " + e.toString())))
  }
}

class HttpConnection(val uri: URI, val ch: Channel, filter: FilterProc ) {

  final val CRLF = "\r\n"

  private def rd_proc(contentLen: Int, bodyChunk: Chunk[Byte]) = {
    var totalChunk = bodyChunk
    val loop = for {
      chunk <- if (contentLen > totalChunk.length) ch.read else ZIO.succeed(Chunk[Byte]())
      _     <- ZIO.effectTotal { totalChunk = totalChunk ++ chunk }
      //_     <- zio.console.putStrLn("read block, size= " + totalChunk.length)
    } yield (totalChunk)
    loop.repeatWhile(_.length < contentLen)
  }

  private def rd_loop2(
    contentLen: Int,
    bodyChunk: Chunk[Byte]
  ): ZIO[ZEnv, Exception, zio.Chunk[Byte]] =
    if (contentLen > bodyChunk.length) {
      ch.read.flatMap(chunk => rd_loop2(contentLen, bodyChunk ++ chunk))
    } else
      ZIO.succeed(bodyChunk)

  private def read_http_header(
    hdr_size: Int,
    cb: Chunk[Byte] = Chunk[Byte]()
  ): ZIO[ZEnv, Exception, Chunk[Byte]] =
    for {
      nextChunk <- if (cb.size < hdr_size) ch.read else ZIO.fail(new HttpResponseHeaderError("header is too big"))
      pos       <- ZIO.effectTotal(new String(nextChunk.toArray).indexOf("\r\n\r\n"))
      resChunk  <- if (pos < 0) read_http_header(hdr_size, cb ++ nextChunk) else ZIO.effectTotal(cb ++ nextChunk)
    } yield (resChunk)

  private def getHTTPResponse = {
    val result = for {
      headerChunk <- read_http_header(HttpConnection.HTTP_HEADER_SZ)
      source      <- ZIO.effect(Source.fromBytes(headerChunk.toArray))
      _           <- ZIO.effect(source.withPositioning(true))
      lines       <- ZIO.effect(source.getLines())
      _ <- if (lines.hasNext == false) ZIO.fail(new HttpResponseHeaderError("no data"))
          else ZIO.succeed(0).unit

      http_line = raw"(HTTP/.+)\s+(\d{3}+)(.*)".r

      headers0 <- lines.next match {
                   case http_line(prot, code, emsg) =>
                     ZIO.effectTotal(Headers("%prot" -> prot, "%code" -> code, "%mesage" -> emsg))
                   case _ => ZIO.fail(new HttpResponseHeaderError("bad response"))

                 }

      attribute_pair = raw"(.{2,100}):\s+(.+)".r

      headers <- ZIO.effect {
                  lines
                    .takeWhile(!_.isEmpty)
                    .foldLeft(headers0)((map, line) => {

                      line match {
                        case attribute_pair(attr, value) => map + (attr.toLowerCase -> value)
                      }

                    })
                }

      pos0 = new String(headerChunk.toArray).indexOf("\r\n\r\n")
      pos  = pos0 + 4

      contentLen <- if (pos0 == -1) ZIO.fail(new HttpResponseHeaderError("bad response(2)"))
                   else
                     ZIO.effect {
                       headers.get("content-length").getOrElse("0")
                     }

      contentLenL <- ZIO.fromTry(Try(contentLen.toLong))

      _ <- if (contentLenL > HttpConnection.MAX_ALLOWED_CONTENT_LEN)
            ZIO.fail(new HttpResponseHeaderError("content len too big"))
          else ZIO.unit

      bodyChunk <- rd_proc(contentLen.toInt, headerChunk.drop(pos))

    } yield (ClientResponse(headers, headers.get("%code").get, bodyChunk))

    result
  }

  def close = ch.close

  ///////////////////////////////////////////////////////////////
  def send(req: ClientRequest): ZIO[zio.ZEnv with MyLogging, Throwable, ClientResponse] = {

    def parseRequest(req: ClientRequest) = ZIO.effect {

      val b = req.body.getOrElse(Chunk[Byte]())
      val r = new StringBuilder

      r ++= req.method.name + " " + req.path + " " + "HTTP/1.1" + CRLF
      r ++= "User-Agent: " + HttpConnection.CLIENT_TAG + CRLF
      r ++= "Host: " + uri.getHost() + CRLF
      r ++= "Accept: */*" + CRLF
      r ++= "Content-Length: " + b.size + CRLF

      req.hdrs.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + CRLF }

      r ++= CRLF
    }

    (for {
      //potentially blocking, if you need to talk to OAUTH2 to propagate headers ...
      req0 <- effectBlocking( filter.run(req) )
      r    <- parseRequest(req0)

      _ <- ch.write(Chunk.fromArray(r.toString.getBytes))

      _ <- MyLogging.trace( "client", "http >>>: " + req0.method + "  " + this.uri.toString() + " ;path = " + req.path )

      _ <- if (req.body.isDefined) ch.write(req.body.get) else ZIO.unit

      data <- getHTTPResponse

      _ <- MyLogging.trace( "client", "http <<<: " + "http code = " + data.httpString + " " + 
                            "bytes=" + data.hdrs.get( "content-length").getOrElse(0) + " text = " + data.asText.substring( 0, 30 ).replace( "\n", "") + " ... " )

    } yield (data)).catchAll(e => ZIO.fail(new HttpConnectionError(e.toString())))

  }
}
