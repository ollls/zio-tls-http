package zhttp

import zio.{ Chunk, IO, ZEnv, ZIO }
import zio.blocking._
//import scala.collection.immutable.ListMap
import java.io.File
import java.io.FileInputStream
import zio.stream.ZStream

object ResponseWriters {

  final val TAG = "zio-nio-tls-http"

  final val CRLF = "\r\n"

////////////////////////////////////////////////////////////////////////////
  def writeNoBodyResponse(
    c: Channel,
    code: StatusCode,
    msg: String,
    close: Boolean
  ): ZIO[ZEnv, Exception, Int] =
    Channel.write(c, Chunk.fromArray(genResponse(code, msg, close).getBytes()))

  /////////////////////////////////////////////////////////////////////////////
  def writeFullResponseFromStream(
    c: Channel,
    rs: Response
  ) = {
    val code   = rs.code
    val stream = rs.stream
    val header = ZStream(genResponseChunked(rs, code, false)).map(str => Chunk.fromArray(str.getBytes()))

    val s0  = stream.map(c => (c.size.toHexString -> c.appended[Byte](('\r')).appended[Byte]('\n')))
    val s1  = s0.map(c => (Chunk.fromArray((c._1 + CRLF).getBytes()) ++ c._2))
    val zs  = ZStream(Chunk.fromArray(("0".toString + CRLF + CRLF).getBytes))
    val res = header ++ s1 ++ zs

    res.foreach { chunk0 =>
      {
        Channel.write(c, chunk0)
      }
    }
  }

  def writeFullResponseBytes(
    c: Channel,
    rs: Response,
    code: StatusCode,
    data: Chunk[Byte],
    close: Boolean
  ): ZIO[ZEnv, Exception, Int] =
    for {
      n <- ZIO.succeed(data.size)
      _ <- Channel.write(c, Chunk.fromArray(getContentResponse(rs, code, n, false).getBytes()))
      _ <- Channel.write(c, data)
    } yield (n)

  ////////////////////////////////////////////////////////////////////////////
  def writeFullResponse(
    c: Channel,
    rs: Response,
    code: StatusCode,
    msg: String,
    close: Boolean
  ): ZIO[ZEnv, Exception, Int] =
    Channel.write(c, Chunk.fromArray(genResponseFromResponse(rs, code, msg, close).getBytes()))

  def writeResponseMethodNotAllowed(c: Channel, allow: String): ZIO[ZEnv, Exception, Int] =
    Channel.write(c, Chunk.fromArray(genResponseMethodNotAllowed(allow).getBytes()))

  def writeResponseUnsupportedMediaType(c: Channel): ZIO[ZEnv, Exception, Int] =
    Channel.write(c, Chunk.fromArray(genResponseUnsupportedMediaType().getBytes()))

  def writeResponseRedirect(c: Channel, location: String): ZIO[ZEnv, Exception, Int] =
    Channel.write(c, Chunk.fromArray(genResponseRedirect(location).getBytes()))

  ///////////////////////////////////////////////////////////
  //chunkSize = tls app packet max len
  def writeBLOBtoChannel(
    c: Channel,
    chunkSize: Int,
    contentType: String,
    fpath: File
  ): ZIO[ZEnv, Exception, Unit] = {
    val result = for {

      buf <- IO.effectTotal(new Array[Byte](chunkSize))

      header <- IO.effect(ResponseWriters.genResponseContentTypeFileHeader(fpath.toString, contentType))

      fpm = effectBlocking(new FileInputStream(fpath)).toManaged(fp => ZIO.effect(fp.close).catchAll(_ => IO.unit))

      _ <- fpm.use { fp =>
            Channel.write(c, Chunk.fromArray(header.getBytes)) *> (effectBlocking(fp.read(buf))
              .flatMap { nBytes =>
                {
                  if (nBytes > 0) {
                    Channel.write(c, Chunk.fromArray(buf).take(nBytes)) *> IO.succeed(nBytes)
                  } else IO.succeed(nBytes)
                }
              })
              .repeat(zio.Schedule.recurWhile(_ > 0))
          }

    } yield ()

    result.refineToOrDie[Exception]
  }

  /*
  private def genBadRequest(code: StatusCode, msg: String): String = {
    val r = new StringBuilder

    r ++= "HTTP/1.1 " + code.value + " " + msg + "\n"
    r ++= "Content-Length: 0\n"
    r ++= "Connection: keep-alive\n"
    r ++= "\n"

    r.toString
  }*/

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
