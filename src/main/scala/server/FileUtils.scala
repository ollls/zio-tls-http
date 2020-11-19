package zhttp

import zio.IO
import zio.ZIO
import zio.ZEnv
import zio.blocking._

import java.net.URI
import java.nio.file.{ Path => JPath }
import java.nio.file.FileSystems
import java.io.FileOutputStream
import MyLogging.MyLogging

object FileUtils {

  val HTTP_CHUNK_SIZE = 32000

  //////////////////////////////////////////////////////////////////////////
  def serverFilePath(raw_path: String, root_folder: String, new_file: Boolean = false) =
    for {
      file_path <- IO.effect {
                    val path             = new URI(raw_path)
                    val file_path: JPath = FileSystems.getDefault().getPath(root_folder, path.getPath)
                    file_path
                  }

      _ <- if (file_path.toFile.isDirectory) {
            IO.fail(new AccessDenied())
          } else IO.succeed(file_path)

      _ <- if (new_file == false && file_path.toFile().exists() == false) {
            IO.fail(new java.io.FileNotFoundException(file_path.toString))
          } else IO.succeed(0).unit
    } yield (file_path)

  /////////////////////////////////////////////////////////////////////////////
  def saveFile(req: Request, folder: String): ZIO[ZEnv, Exception, Response ] = {

    val T = for {

      contentLen <- IO(req.contentLen.toLong).catchAll { e =>
                     IO.fail(e)
                   }

      file_path <- serverFilePath(req.path, folder, new_file = true)

      _ <- zio.console.putStrLn("file path " + file_path)

      fp <- effectBlocking(new FileOutputStream(file_path.toFile))

      w_sz <- zio.Ref.make(0L)

      loop = for {

        sz <- w_sz.get
        chunk <- if (sz == 0 && req.body.size != 0) IO(req.body) //read prefetch from request body first. 
                else {
                  req.ch.read  //continue reading stream
                }

        _ <- effectBlocking { fp.write(chunk.toArray) }
        _ <- w_sz.update(_ + chunk.size)

        size <- w_sz.get
      } yield (size)

      _ <- loop.repeat(zio.Schedule.recurWhile(_ < contentLen))

      _ <- effectBlocking(fp.close)

    } yield (Response.Ok)

    T.refineToOrDie[Exception]

  }

////////////////////////////////////////////////////////////////////////////
  def loadFile( req : Request, folder: String ): ZIO[ZEnv with MyLogging, Throwable, Response ] = {
    //val  packet_sz = 12192

    val c = req.ch
    val raw_path = req.path

    val packet_sz = HTTP_CHUNK_SIZE

    val result = for {

      file_path <- serverFilePath(raw_path, folder)
      file_name <- IO.effect { file_path.getFileName }

      _ <- if (file_name.toString.endsWith(".jpg"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "image/jpeg", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".ttf"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "font/ttf", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".eot"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "application/vnd.ms-fontobject", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".woff"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "font/woff", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".svg"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "image/svg+xml", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".gif"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "image/gif", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".png"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "image/png", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".html") || file_name.toString.endsWith(".txt"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "text/html", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".css"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "text/css", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else if (file_name.toString.endsWith(".js") || file_name.toString.endsWith(".js.download"))
            ResponseWriters.writeBLOBtoChannel(c, packet_sz, "application/javascript", file_path.toFile) *>
            Logs.log_access( req, StatusCode.OK, req.body.size )
          else {
             ResponseWriters.writeBLOBtoChannel(c, packet_sz, "text/html", file_path.toFile) *>
             Logs.log_access( req, StatusCode.OK, req.body.size )
            //ResponseWriters.writeResponseUnsupportedMediaType(c) *> 
            //Logs.log_access( req, StatusCode.UnsupportedMediaType, req.body.size )
          }  

    } yield (NoResponse)

    result
  }
}
