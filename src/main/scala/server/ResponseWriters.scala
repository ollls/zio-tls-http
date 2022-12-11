package zhttp

import zio.{ Chunk, IO, ZIO, Task }
import zhttp.netio._
import java.nio.ByteBuffer

//import scala.collection.immutable.ListMap
import java.io.File
import java.io.FileInputStream
import zio.stream.ZStream
import zio.ZIO.attemptBlocking

object ResponseWriters {

  final val TAG = "zio-nio-tls-http"

  final val CRLF = "\r\n"

////////////////////////////////////////////////////////////////////////////
  def writeNoBodyResponse(
    c: IOChannel,
    code: StatusCode,
    msg: String,
    close: Boolean
  ): Task[Int] =
    c.write( ByteBuffer.wrap(genResponse(code, msg, close).getBytes() ) )

  /////////////////////////////////////////////////////////////////////////////
  def writeFullResponseFromStream[MyEnv](
    c: IOChannel,
    rs: Response
  ) = {
    val code   = rs.code
    val stream = rs.streamWith[MyEnv]
    val header = ZStream(genResponseChunked(rs, code, false)).map(str => Chunk.fromArray(str.getBytes()))

    val s0  = stream.map(c => (c.size.toHexString -> c.appended[Byte](('\r')).appended[Byte]('\n')))
    val s1  = s0.map(c => (Chunk.fromArray((c._1 + CRLF).getBytes()) ++ c._2))
    val zs  = ZStream(Chunk.fromArray(("0".toString + CRLF + CRLF).getBytes))
    val res = header ++ s1 ++ zs

    res.foreach { chunk0 =>
      {
        c.write(ByteBuffer.wrap(chunk0.toArray ) )
      }
    }
  }

  def writeFullResponseBytes(
    c: IOChannel,
    rs: Response,
    code: StatusCode,
    data: Chunk[Byte],
    close: Boolean
  ): Task[Int] = //ZIO[Any, Exception, Int] =
    for {
      n <- ZIO.succeed(data.size)
      _ <- c.write( ByteBuffer.wrap( getContentResponse(rs, code, n, false).getBytes() ) )
      _ <- c.write( ByteBuffer.wrap (data.toArray ) )
    } yield (n)

  ////////////////////////////////////////////////////////////////////////////
  def writeFullResponse(
    c: IOChannel,
    rs: Response,
    code: StatusCode,
    msg: String,
    close: Boolean
  ): Task[Int] =
    c.write( ByteBuffer.wrap( genResponseFromResponse(rs, code, msg, close).getBytes()) )

  def writeResponseMethodNotAllowed(c: IOChannel, allow: String): Task[Int] =
    c.write(ByteBuffer.wrap(genResponseMethodNotAllowed(allow).getBytes()))

  def writeResponseUnsupportedMediaType(c: IOChannel): Task[Int] =
    c.write( ByteBuffer.wrap(genResponseUnsupportedMediaType().getBytes()))

  def writeResponseRedirect(c: IOChannel, location: String): Task[Int] =
    c.write( ByteBuffer.wrap(genResponseRedirect(location).getBytes()))

  ///////////////////////////////////////////////////////////
  //chunkSize = tls app packet max len
  def writeBLOBtoChannel(
    c: IOChannel,
    chunkSize: Int,
    contentType: String,
    fpath: File
  ): ZIO[Any, Exception, Unit] = {
    val result = for {

      buf <- ZIO.succeed(new Array[Byte](chunkSize))

      header <- ZIO.attempt(ResponseWriters.genResponseContentTypeFileHeader(fpath.toString, contentType))

      fpm = ZIO.acquireRelease( attemptBlocking( new FileInputStream(fpath))) ( fp => ZIO.attempt(fp.close ).catchAll(_ => ZIO.unit) )

      y <- ZIO.scoped { fpm.flatMap{ fp =>
            c.write( ByteBuffer.wrap(header.getBytes)) *> (attemptBlocking(fp.read(buf))
              .flatMap { nBytes =>
                {
                  if (nBytes > 0) {
                    c.write(ByteBuffer.wrap(Chunk.fromArray(buf).take(nBytes).toArray) ) *> ZIO.succeed(nBytes)
                  } else ZIO.succeed(nBytes)
                }
              })
              .repeat(zio.Schedule.recurWhile(_ > 0))
          } }

    } yield ()

    result.refineToOrDie[Exception]
  }


  ///////////////////////////////////////////////////////////////////////
  private def getContentResponse(resp: Response, code: StatusCode, contLen: Int, close: Boolean): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")
    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))
    val r = new StringBuilder

    r ++= "HTTP/1.1 " + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + contLen.toString() + CRLF

    resp.headers.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + CRLF }

    if (close)
      r ++= "Connection: close" + CRLF
    else
      r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()
  }

  ///////////////////////////////////////////////////////////////////////
  private def genResponseFromResponse(resp: Response, code: StatusCode, msg: String, close: Boolean): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 " + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + msg.length + CRLF

    resp.headers.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + CRLF }

    if (close)
      r ++= "Connection: close" + CRLF
    else
      r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r ++= msg

    r.toString()
  }

  ///////////////////////////////////////////////////////////////////////
  private def genResponseChunked(resp: Response, code: StatusCode, close: Boolean): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 " + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    resp.headers.foreach { case (key, value) => r ++= Headers.toCamelCase(key) + ": " + value + CRLF }
    if (close)
      r ++= "Connection: close" + CRLF
    else
      r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()
  }

  ///////////////////////////////////////////////////////////////////////
  private def genResponse(code: StatusCode, msg: String, close: Boolean): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 " + code.value.toString + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: " + msg.length + CRLF
    if (close)
      r ++= "Connection: close" + CRLF
    else
      r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r ++= msg

    r.toString()
  }

  ///////////////////////////////////////////////////////////////////////
  private def genResponseUnsupportedMediaType(): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 415 Unsupported Media Type" + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: 0" + CRLF
    r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()
  }

  ////////////////////////////////////////////////////////////////////////
  private def genResponseRedirect(location: String): String = {

    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 303 Redirect" + CRLF
    r ++= "Location: " + location + CRLF
    r ++= "Cache-Control: no-cache, no-store, must-revalidate" + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Length: 0" + CRLF
    r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()

  }

  ///////////////////////////////////////////////////////////////////////
  // example: Allow: GET, POST, HEAD
  private def genResponseMethodNotAllowed(allow: String): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val r = new StringBuilder

    r ++= "HTTP/1.1 405 Method not allowed" + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Allow: " + allow + CRLF
    r ++= "Content-Length: 0" + CRLF
    r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()
  }

  //////////////////////////////////////////////////////////////////////////////
  def genResponseContentTypeFileHeader(fpath: String, cont_type: String): String = {
    val dfmt = new java.text.SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss")

    dfmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT"))

    val fp        = new File(fpath)
    val file_size = fp.length()

    val r = new StringBuilder

    r ++= "HTTP/1.1 200 OK" + CRLF
    r ++= "Date: " + dfmt.format(new java.util.Date()) + " GMT" + CRLF
    r ++= "Server: " + TAG + CRLF
    r ++= "Content-Type: " + cont_type + CRLF
    r ++= "Content-Length: " + file_size + CRLF
    r ++= "Connection: keep-alive" + CRLF
    r ++= CRLF

    r.toString()

  }

}
