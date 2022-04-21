package zhttp.clients

import zio.ZIO
import zio.ZEnv
import zio.Chunk
import zio.json._
import zio.stream.ZStream
import zio.stream.ZSink
import zio.stream.ZPipeline

import zhttp.{ Channel, TcpChannel, TlsChannel }
import zhttp.Method
import java.net.URI
import javax.net.ssl.SSLContext
import nio.SocketAddress
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import nio.channels.AsynchronousTlsByteChannel
import nio.channels.AsynchronousSocketChannel
import nio.channels.AsynchronousChannelGroup
import java.security.KeyStore
import zhttp.Headers
import zhttp.HttpRouter
import zhttp.ContentType
import zhttp.Cookie
import zhttp.MyLogging
import zhttp.MyLogging.MyLogging
import zhttp.StatusCode

import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import java.io.FileInputStream
import java.io.File
import zio.ZIO.attemptBlocking

sealed case class HttpConnectionError(msg: String)     extends Exception(msg)
sealed case class HttpResponseHeaderError(msg: String) extends Exception(msg)

case class ClientResponse(
  val hdrs: Headers,
  val code: StatusCode,
  val stream: ZStream[ZEnv, Throwable, Chunk[Byte]] = ZStream.empty
) {
  def protocol    = hdrs.get("%prot").getOrElse("")
  def httpString  = code.toString + " " + hdrs.get("%message").getOrElse("")
  def isKeepAlive = hdrs.get("Connection").getOrElse("").equalsIgnoreCase("keep-alive")

  def body = stream.runCollect.map(_.flatten)

  def bodyAsText: ZIO[ZEnv, Throwable, String] =
    for {
      b <- body
      a <- ZIO.attempt(new String(b.toArray))
    } yield (a)

  def fromJSON[A: JsonDecoder] =
    for {
      b <- body
      obj <- ZIO.attempt {
              new String(b.toArray).fromJson[A]
            }
    } yield (obj)

  //TODO - parse cookie to model.Cookie
  def cookie = hdrs.getMval("Set-Cookie")
}

case class ClientRequest(
  val method: Method,
  val path: String,
  val hdrs: Headers = Headers(),
  val stream: ZStream[ZEnv, Throwable, Chunk[Byte]] = ZStream.empty
) {

  def body(stream: ZStream[ZEnv, Throwable, Chunk[Byte]]): ClientRequest =
    ClientRequest(this.method, this.path, this.hdrs, stream)

  def asJsonBody[B: JsonEncoder](body0: B): ClientRequest = {
    val zs0 = ZStream(Chunk.fromArray(body0.toJson.getBytes))
    body(zs0).contentType(ContentType.JSON)
  }

  def asTextBody(body0: String): ClientRequest = {
    val zs0 = ZStream(Chunk.fromArray(body0.getBytes))
    body(zs0).contentType(ContentType.Plain)
  }

  def hdr(hdr: Headers): ClientRequest = new ClientRequest(this.method, this.path, hdrs ++ hdr, this.stream)

  def hdr(pair: (String, String)) = new ClientRequest(this.method, this.path, hdrs + pair, this.stream)

  def contentType(type0: ContentType) =
    new ClientRequest(this.method, this.path, hdrs + ("content-type" -> type0.toString()), this.stream)

  def cookie(cookie: Cookie) = {
    val pair = ("cookie" -> cookie.toString())
    new ClientRequest(this.method, this.path, hdrs + pair, this.stream)
  }

  def isChunked: Boolean = transferEncoding().exists(_.equalsIgnoreCase("chunked"))

  def transferEncoding(): Set[String] = hdrs.getMval("transfer-encoding")

  def transferEncoding(vals0: String*): ClientRequest =
    new ClientRequest(
      this.method,
      this.path,
      vals0.foldLeft(this.hdrs)((h, v) => h + ("transfer-encoding" -> v)),
      this.stream
    )

}

//Request to Request, enriched with headers
case class FilterProc(run: ClientRequest => ZIO[ZEnv with MyLogging, Throwable, ClientRequest])

object HttpConnection {

  val HTTP_HEADER_SZ          = 8096 * 2
  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100
  val TLS_PROTOCOL_TAG        = "TLSv1.2"
  val CLIENT_TAG              = "zio-tls-http"

  private def loadDefaultKeyStore(): KeyStore = {
    val relativeCacertsPath = "/lib/security/cacerts".replace("/", File.separator);
    val filename            = System.getProperty("java.home") + relativeCacertsPath;
    val is                  = new FileInputStream(filename);

    val keystore = KeyStore.getInstance(KeyStore.getDefaultType());
    val password = "changeit";
    keystore.load(is, password.toCharArray());

    keystore;
  }

  private def buildSSLContext(protocol: String, JKSkeystore: String, password: String) = {
    //JKSkeystore == null, only if blind trust was requested

    val sslContext: SSLContext = SSLContext.getInstance(protocol)

    val keyStore = if (JKSkeystore == null) {
      loadDefaultKeyStore()
    } else {
      val keyStore: KeyStore = KeyStore.getInstance("JKS")
      val ks                 = new java.io.FileInputStream(JKSkeystore)
      keyStore.load(ks, password.toCharArray())
      keyStore
    }

    val trustMgrs = if (JKSkeystore == null) {
      Array[TrustManager](new X509TrustManager() {
        def getAcceptedIssuers(): Array[X509Certificate]                   = null
        def checkClientTrusted(c: Array[X509Certificate], a: String): Unit = ()
        def checkServerTrusted(c: Array[X509Certificate], a: String): Unit = ()
      })

    } else {
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      tmf.init(keyStore)
      tmf.getTrustManagers()
    }

    val pwd = if (JKSkeystore == null) "changeit" else password

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(keyStore, pwd.toCharArray())
    sslContext.init(kmf.getKeyManagers(), trustMgrs, null);

    sslContext
  }

  private def buildSSLContextM(
    protocol: String,
    jKSkeystore: String,
    password: String
  ) = attemptBlocking(buildSSLContext(protocol, jKSkeystore, password)).refineToOrDie[Exception]

  private def connectSSL(
    host: String,
    port: Int,
    group: AsynchronousChannelGroup,
    blindTrust: Boolean = false,
    trustKeystore: String = null,
    password: String = ""
  ): ZIO[zio.ZEnv, Exception, Channel] = {
    val T = for {
      address <- SocketAddress.inetSocketAddress(host, port)
      ssl_ctx <- if (trustKeystore == null && blindTrust == false)
                  attemptBlocking(SSLContext.getDefault()).refineToOrDie[Exception]
                else buildSSLContextM(TLS_PROTOCOL_TAG, trustKeystore, password)
      ch     <- if (group == null) AsynchronousSocketChannel() else AsynchronousSocketChannel(group)
      _      <- ch.connect(address).mapError(e => HttpConnectionError(e.toString))
      tls_ch <- AsynchronousTlsByteChannel(ch, ssl_ctx)
    } yield (tls_ch)

    T.map(c => new TlsChannel(c))
  }

  private def connectPlain(
    host: String,
    port: Int,
    group: AsynchronousChannelGroup
  ): ZIO[zio.ZEnv, Exception, Channel] = {
    val T = for {
      address <- SocketAddress.inetSocketAddress(host, port)
      ch      <- if (group == null) AsynchronousSocketChannel() else AsynchronousSocketChannel(group)
      _       <- ch.connect(address).mapError(e => HttpConnectionError(e.toString))
    } yield (ch)

    T.map(c => new TcpChannel(c)).refineToOrDie[Exception]
  }

  def connect(
    url: String,
    socketGroup: AsynchronousChannelGroup = null,
    tlsBlindTrust: Boolean = false,
    trustKeystore: String = null,
    password: String = ""
  ): ZIO[ZEnv, HttpConnectionError, HttpConnection] =
    connectWithFilter(url, socketGroup, req => ZIO.succeed(req), tlsBlindTrust, trustKeystore, password)

  def connectWithFilter(
    url: String,
    socketGroup: AsynchronousChannelGroup,
    filter: ClientRequest => ZIO[ZEnv with MyLogging, Throwable, ClientRequest],
    tlsBlindTrust: Boolean = false,
    trustKeystore: String = null,
    password: String = ""
  ) = {
    val u    = new URI(url)
    val port = if (u.getPort == -1) 443 else u.getPort
    (if (u.getScheme().equalsIgnoreCase("https")) {
       val ss = connectSSL(u.getHost(), port, socketGroup, tlsBlindTrust, trustKeystore, password)
         .map(new HttpConnection(u, _, FilterProc(filter)))
       ss
     } else if (u.getScheme().equalsIgnoreCase("http")) {
       val ss = connectPlain(u.getHost(), port, socketGroup).map(new HttpConnection(u, _, FilterProc(filter)))
       ss
     } else
       throw new Exception("HttpConnection: Unsupported scheme - " + u.getScheme()))
      .catchAll(e => ZIO.fail(new HttpConnectionError(url + " " + e.getMessage )))
  }
}

class HttpConnection(val uri: URI, val ch: Channel, filter: FilterProc) {

  final val CRLF = "\r\n"

  private def rd_proc(contentLen: Int, bodyChunk: Chunk[Byte]) = {
    var totalChunk = bodyChunk
    val loop = for {
      chunk <- if (contentLen > totalChunk.length) Channel.read(ch) else ZIO.succeed(Chunk[Byte]())
      _     <- ZIO.succeed { totalChunk = totalChunk ++ chunk }
    } yield (totalChunk)
    loop.repeatWhile(_.length < contentLen)
  }

  private def read_http_header(
    hdr_size: Int,
    cb: Chunk[Byte] = Chunk[Byte]()
  ): ZIO[ZEnv, Exception, Chunk[Byte]] =
    for {
      nextChunk <- if (cb.size < hdr_size) Channel.read(ch)
                  else ZIO.fail(new HttpResponseHeaderError("header is too big"))
      pos      <- ZIO.succeed(new String(nextChunk.toArray).indexOf("\r\n\r\n"))
      resChunk <- if (pos < 0) read_http_header(hdr_size, cb ++ nextChunk) else ZIO.succeed(cb ++ nextChunk)
    } yield (resChunk)

  private def getHTTPResponse2 = {
    val http_line   = raw"(HTTP/.+)\s+(\d{3}+)(.*)".r
    val header_pair = raw"(.{2,100}):\s+(.+)".r
    val rd_stream   = ZStream.repeatZIO(Channel.read(ch)).flatMap(ZStream.fromChunk(_))
    val r = rd_stream.peel(ZSink.fold(Chunk[Byte]()) { c =>
      !c.endsWith("\r\n\r\n")
    }((z, i: Byte) => z :+ i))

    for {
      response <- ZIO.scoped {
                   r.flatMap {
                   case (header_bytes, body_stream) =>
                     val strings =
                       ZStream.fromChunk(header_bytes).via(ZPipeline.usASCIIDecode >>> ZPipeline.splitLines)
                     val hdrs = strings.runFold(Headers())((hdrs, line) => {
                       line match {
                         case http_line(prot, code, emsg) =>
                           hdrs ++ Headers("%prot" -> prot, "%code" -> code, "%message" -> emsg)
                         case header_pair(attr, value) => hdrs + (attr.toLowerCase -> value)
                         case _                        => hdrs
                       }
                     })

                     for {
                       h <- hdrs
                       isChunked <- ZIO.succeed(
                                     h.getMval("transfer-encoding").exists(_.equalsIgnoreCase("chunked"))
                                   )
                       validate <- ZIO.succeed(
                                    h.get("%prot")
                                      .flatMap(_ => h.get("%code").flatMap(_ => h.get("%message")))
                                  )
                       _ <- if (validate.isDefined) ZIO.unit
                           else ZIO.fail(new HttpResponseHeaderError("Invalid http response"))

                       contentLen  <- ZIO.succeed(h.get("content-length").getOrElse("0"))
                       contentLenL <- ZIO.fromTry(scala.util.Try(contentLen.toLong)).refineToOrDie[Exception]
                       code        <- ZIO.succeed(h.get("%code").get)

                       code_i <- ZIO.fromTry(scala.util.Try(code.toInt))

                       stream <- if (isChunked)
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
                                          .take(contentLenL).mapChunks( c => Chunk.single(c) )  
                                  )

                     } yield (new ClientResponse(h, StatusCode(code_i), stream))

                    }
                 }

    } yield (response)
  }

 
  def close = Channel.close(ch)

  def send(req: ClientRequest): ZIO[zio.ZEnv with MyLogging, Throwable, ClientResponse] =
    for {
      req0 <- filter.run(req)
      response <- if (req.isChunked) sendChunked(req0)
                 else sendBody(req0)

    } yield (response)

  ///////////////////////////////////////////////////////////////
  private def sendChunked(req: ClientRequest): ZIO[zio.ZEnv with MyLogging, Throwable, ClientResponse] = {

    def genRequestChunked(resp: ClientRequest): String = {
      val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")
      dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))
      val r = new StringBuilder
      r ++= req.method.name + " " + req.path + " " + "HTTP/1.1" + CRLF
      r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
      r ++= "User-Agent: " + HttpConnection.CLIENT_TAG + CRLF
      resp.hdrs.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + CRLF }
      r ++= CRLF

      println( r.toString() )
      r.toString()
    }

    val stream = req.stream
    val header = ZStream(genRequestChunked(req)).map(str => Chunk.fromArray(str.getBytes()))

    val s0  = stream.map(c => (c.size.toHexString -> c.appended[Byte](('\r')).appended[Byte]('\n')))
    val s1  = s0.map(c => (Chunk.fromArray((c._1 + CRLF).getBytes()) ++ c._2))
    val zs  = ZStream(Chunk.fromArray(("0".toString + CRLF + CRLF).getBytes))
    val res = header ++ s1 ++ zs

    (for {
      _        <- res.foreach(chunk0 => { Channel.write(ch, chunk0) } )
      response <- getHTTPResponse2
      _ <- MyLogging.debug(
            "client",
            "http <<<: " + "http code = " + response.httpString + " " +
              "bytes = " + req.transferEncoding().mkString(",")
              //with stream only client can evaluate the text, can't log text here
              //+ " text = " + text
              //.substring(0, if (text.length() < 30) text.length() else 30)
              //.replace("\n", "") + " ... "
          )
    } yield (response)).catchAll(e => ZIO.fail(new HttpConnectionError(e.toString())))

  }

  ///////////////////////////////////////////////////////////////
  private def sendBody(req: ClientRequest): ZIO[zio.ZEnv with MyLogging, Throwable, ClientResponse] = {

    def parseRequest(req: ClientRequest, bodySize: Int) = ZIO.succeed {
      val r = new StringBuilder
      r ++= req.method.name + " " + req.path + " " + "HTTP/1.1" + CRLF
      r ++= "User-Agent: " + HttpConnection.CLIENT_TAG + CRLF
      r ++= "Host: " + uri.getHost() + CRLF
      r ++= "Accept: */*" + CRLF
      r ++= "Content-Length: " + bodySize + CRLF
      req.hdrs.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + CRLF }
      r ++= CRLF
    }

    (for {
      body <- req.stream.flatMap(chunk => ZStream.fromChunk(chunk)).runCollect
      r    <- parseRequest(req, body.size)

      _ <- Channel.write(ch, Chunk.fromArray(r.toString.getBytes))

      _ <- MyLogging.debug("client", "http >>>: " + req.method + "  " + this.uri.toString() + " ;path = " + req.path)

      _ <- if (body.isEmpty == false) Channel.write(ch, body) else ZIO.unit

      response <- getHTTPResponse2
     
      _ <- MyLogging.debug(
            "client",
            "http <<<: " + "http code = " + response.httpString + " " +
              "bytes = " + response.hdrs.get("content-length").getOrElse(0) 
              //with stream only client can evaluate the text, can't log text here
              //+ " text = " + "text"
              //.substring(0, if ("text".length() < 30) "text".length() else 30)
              //.replace("\n", "") + " ... "
          )

    } yield (response)).catchAll(e => ZIO.fail(new HttpConnectionError(e.toString())))
  }
}
