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


sealed case class HttpConnectionError(msg: String)     extends Exception(msg)
sealed case class HttpResponseHeaderError(msg: String) extends Exception(msg)

case class ClientResponse( val hdrs : Headers, val code : String, body : Chunk[Byte] )
{
   def protocol = hdrs.get( "%prot" ).getOrElse("")
   def httpString = code.toString + " " + hdrs.get( "%message" ).getOrElse("")
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

  def bodyAsText(body0: String): ClientRequest =
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

object HttpConnection {

  val HTTP_HEADER_SZ          = 8096 * 2
  val MAX_ALLOWED_CONTENT_LEN = 1048576 * 100

  def buildSSLContext(
    protocol: String,
    JKSkeystore: String,
    password: String
  ) = {

    val test = effectBlocking {

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

    test.refineToOrDie[Exception]

  }

  def connectSSL( host: String, port: Int, trustKeystore: String = null, password: String = "" ): ZIO[zio.ZEnv, Exception, Channel] = {
    val T = for {
      address <- SocketAddress.inetSocketAddress( host, port )
      ssl_ctx <- if( trustKeystore == null ) effectBlocking( SSLContext.getDefault() ).refineToOrDie[Exception]
                 else buildSSLContext("TLSv1.2", trustKeystore, password)
      ch      <- AsynchronousSocketChannel()
      _       <- ch.connect(address).mapError(e => HttpConnectionError(e.toString))
      tls_ch  <- AsynchronousTlsByteChannel(ch, ssl_ctx)
    } yield (tls_ch)

    T.map(c => new TlsChannel(c))
  }

  def connect(url: String, trustKeystore: String, password: String) = {
    val u = new URI(url)
    (if (u.getScheme().equalsIgnoreCase("https")) {
      connectSSL(u.getHost(),u.getPort, trustKeystore, password).map(new HttpConnection( u, _))
    } else {
      throw new Exception("HttpConnection: Unsupported scheme - " + u.getScheme())
    }).catchAll( e => ZIO.fail( new HttpConnectionError( e.toString() ) ) )
  }

}


class HttpConnection( val uri : URI, val ch: Channel) {


  final val CRLF = "\r\n"

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
      source <- ZIO.effect(Source.fromBytes(headerChunk.toArray))
      _      <- ZIO.effect(source.withPositioning(true))
      lines  <- ZIO.effect(source.getLines())
      _ <- if (lines.hasNext == false) ZIO.fail(new HttpResponseHeaderError( "no data") )
          else ZIO.succeed(0).unit

       //http_line = raw"([A-Z]{3,8})\s+(.+)\s+(HTTP/.+)".r    
       http_line = raw"(HTTP/.+)\s+(\d{3}+)(.*)".r 

       headers0 <- lines.next match {
                     case http_line( prot, code, emsg ) =>
                       ZIO.effectTotal( Headers( "%prot" -> prot, "%code" -> code, "%mesage" -> emsg ) )
                     case _ => ZIO.fail( new HttpResponseHeaderError( "bad response") )

                   }
       
       attribute_pair = raw"(.{2,100}):\s+(.+)".r            

       headers <- ZIO.effect {
                                   lines
                                     .takeWhile(!_.isEmpty)
                                     .foldLeft(  headers0 )((map, line) => {

                                       line match {
                                         case attribute_pair(attr, value) => map + (attr.toLowerCase -> value)
                                       }

                                     })
                                 }   
                                 
      pos0 = new String( headerChunk.toArray).indexOf("\r\n\r\n")
      pos  = pos0 + 2         
      
      contentLen <-     if( pos0 == -1 )  ZIO.fail( new HttpResponseHeaderError( "bad response(2)") ) 
                        else ZIO.effect {
                          headers.get("content-length").getOrElse("0")
                        }

      contentLenL <- ZIO.fromTry( Try( contentLen.toLong ) )

       _  <- if ( contentLenL > HttpConnection.MAX_ALLOWED_CONTENT_LEN ) 
                ZIO.fail( new HttpResponseHeaderError( "content len too big" ) )
                else ZIO.unit  

      bodyChunk <- rd_loop2( contentLen.toInt, headerChunk.drop(pos))


    } yield ( ClientResponse( headers,  headers.get( "%code").get, bodyChunk ) )

    result
  }

  def close = ch.close

  ///////////////////////////////////////////////////////////////
  def send(req: ClientRequest) : ZIO[zio.ZEnv,Throwable,ClientResponse] = {

    val b = req.body.getOrElse(Chunk[Byte]())
    val r = new StringBuilder

    r ++= req.method.name + " " + req.path + " " + "HTTP/1.1" + CRLF
    r ++= "User-Agent: zio-tls-http-client" + CRLF
    r ++= "Host: " + uri.getHost() + CRLF 
    r ++= "Accept: */*" + CRLF
    r ++= "Content-Length: " + b.size + CRLF
    r ++= CRLF

    req.hdrs.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + CRLF }

    for {
      _ <- ch.write(Chunk.fromArray(r.toString.getBytes))
      _ <- if (req.body.isDefined) ch.write(req.body.get) else ZIO.unit

      data <- getHTTPResponse

    } yield (data)

  }
}
