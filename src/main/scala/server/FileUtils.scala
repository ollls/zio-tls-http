package zhttp

import zio.IO
import zio.ZIO
import zio.stream._
import zio.Chunk

import java.net.URI
import java.nio.file.{Path => JPath}
import java.nio.file.FileSystems
import zhttp.dsl.Path
import zio.logging.backend.SLF4J

object FileUtils {

  val HTTP_CHUNK_SIZE = 64000

  //////////////////////////////////////////////////////////////////////////
  def serverFilePath_(raw_path: Path, root_folder: String, new_file: Boolean = false) =
    for {
      path <- ZIO.attempt(new URI(raw_path.toString))
      file_path <- ZIO.attempt {
        val file_path: JPath = FileSystems.getDefault().getPath(root_folder, path.getPath)
        file_path
      }
      _ <-
        if (file_path.toFile.isDirectory) {
          ZIO.fail(new AccessDenied())
        } else ZIO.succeed(file_path)

      _ <-
        if (new_file == false && file_path.toFile().exists() == false) {
          ZIO.fail(new java.io.FileNotFoundException(file_path.toString))
        } else ZIO.succeed(0).unit
    } yield (file_path)

  //////////////////////////////////////////////////////////////////////////
  def serverFilePath(raw_path: String, root_folder: String, new_file: Boolean = false) =
    for {
      file_path <- ZIO.attempt {
        val path             = new URI(raw_path)
        val file_path: JPath = FileSystems.getDefault().getPath(root_folder, path.getPath)
        file_path
      }
      _ <-
        if (file_path.toFile.isDirectory) {
          ZIO.fail(new AccessDenied())
        } else ZIO.succeed(file_path)

      _ <-
        if (new_file == false && file_path.toFile().exists() == false) {
          ZIO.fail(new java.io.FileNotFoundException(file_path.toString))
        } else ZIO.succeed(0).unit
    } yield (file_path)

  private def headerStream(contentType: String, file_path: String) =
    ZStream(ResponseWriters.genResponseContentTypeFileHeader(file_path.toString, contentType))
      .map(s => Chunk.fromArray(s.getBytes()))

  def httpFileStream(req: Request, folder: String): ZIO[Any, Exception, ZStream[Any, Throwable, Chunk[Byte]]] = {

    val raw_path = req.path
    (for {
      file_path <- serverFilePath(raw_path, folder)
      file_name <- ZIO.attempt { file_path.getFileName }
      body      <- req.body
      fstream0  <- ZIO.attempt(ZStream.fromFile(file_path.toFile(), HTTP_CHUNK_SIZE))

      fstream <- ZIO.attempt(fstream0.grouped(HTTP_CHUNK_SIZE))

      //https://zio.dev/zio-logging/slf4j
      //access
      //_ <- Logs.log_access(req, StatusCode.OK, body.size)
       _ <- ZIO.logInfo( req.method.toString + " " + req.uri.toString() + " " + StatusCode.OK.value + " " + body.size ) @@ zio.logging.loggerName("access")
      s <-
        if (file_name.toString.endsWith(".jpg")) {
          ZIO.attempt(headerStream("image/jpeg", file_path.toString) ++ fstream)
        } else if (file_name.toString.endsWith(".ttf"))
          ZIO.attempt(headerStream("font/ttf", file_path.toString) ++ fstream)
        else if (file_name.toString.endsWith(".eot"))
          ZIO.attempt(headerStream("application/vnd.ms-fontobject", file_path.toString) ++ fstream)
        else if (file_name.toString.endsWith(".woff"))
          ZIO.attempt(headerStream("font/woff", file_path.toString) ++ fstream)
        else if (file_name.toString.endsWith(".svg"))
          ZIO.attempt(headerStream("image/svg+xml", file_path.toString) ++ fstream)
        else if (file_name.toString.endsWith(".gif"))
          ZIO.attempt(headerStream("image/gif", file_path.toString) ++ fstream)
        else if (file_name.toString.endsWith(".png"))
          ZIO.attempt(headerStream("image/png", file_path.toString) ++ fstream)
        else if (file_name.toString.endsWith(".html") || file_name.toString.endsWith(".txt"))
          ZIO.attempt(headerStream("text/html", file_path.toString) ++ fstream)
        else if (file_name.toString.endsWith(".css"))
          ZIO.attempt(headerStream("text/css", file_path.toString) ++ fstream)
        else if (file_name.toString.endsWith(".js") || file_name.toString.endsWith(".js.download"))
          ZIO.attempt(headerStream("application/javascript", file_path.toString) ++ fstream)
        else {
          ZIO.attempt(headerStream("text/html", file_path.toString) ++ fstream)
        }
    } yield (s)).refineToOrDie[Exception]

  }
  /*
////////////////////////////////////////////////////////////////////////////
  def loadFile(req: Request, folder: String): ZIO[ZEnv with MyLogging, Throwable, Response] = {
    //val  packet_sz = 12192

    val c        = req.ch
    val raw_path = req.path

    val packet_sz = HTTP_CHUNK_SIZE

    val result = for {

      file_path <- serverFilePath(raw_path, folder)
      file_name <- IO.effect { file_path.getFileName }

      body <- req.body

      _ <- if (file_name.toString.endsWith(".jpg"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "image/jpeg", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".ttf"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "font/ttf", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".eot"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "application/vnd.ms-fontobject", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".woff"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "font/woff", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".svg"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "image/svg+xml", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".gif"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "image/gif", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".png"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "image/png", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".html") || file_name.toString.endsWith(".txt"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "text/html", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".css"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "text/css", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else if (file_name.toString.endsWith(".js") || file_name.toString.endsWith(".js.download"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "application/javascript", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
          else {
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "text/html", file_path.toFile) *>
              Logs.log_access(req, StatusCode.OK, body.size)
            //ResponseWriters.writeResponseUnsupportedMediaType(c) *>
            //Logs.log_access( req, StatusCode.UnsupportedMediaType, req.body.size )
          }

    } yield (NoResponse)

    result
  }
   */
}
