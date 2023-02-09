package zhttp

import java.io.FileOutputStream
import zio.{ZIO, Task, Ref, Chunk}
import zio.stream.{ZStream, ZChannel, ZPipeline}
import zhttp.{Headers, ContentType, Request}
import scala.util.control.Breaks._
import java.util.Arrays

object MultiPart {

  private def extractBoundaryFromMultipart(txt: String) = {
    val opt = txt.split(";")
    if (opt.length == 2) {
      val opt2 = opt(1).split("=")
      if (opt2.length == 2) "--" + opt2(1)
      else ""
    } else ""
  }

//Parse headers from chunk
  private def parseMPHeaders(h: Headers, mpblock: Chunk[Byte], boundary: String): (Boolean, Headers, Chunk[Byte]) = {
    var h_out        = h
    val lines        = new String(mpblock.toArray).split("\\R", 128) // .split("\\R+")
    var endOfHeaders = false
    var dataI        = 0;
    breakable {
      for (i <- 0 to lines.length - 1) {
        val v = lines(i).split(":")
        dataI += lines(i).length + 2
        if (v.length == 1) { endOfHeaders = true; break }
        h_out = h_out + (v(0), v(1))
      }
    }
    (endOfHeaders, h_out, mpblock.drop(dataI)) // false when hedaers are expected in the next chunk
  }

  private def doMultiPart_scanAndDropBoundary(chunk: Chunk[Byte], boundary: String): Chunk[Byte] = {
    var bI     = 0
    var cI     = 0
    var bFound = false
    breakable {
      for (c <- chunk) {
        cI += 1
        if (bI >= boundary.length()) {
          bFound = true
          break
        }
        if (c == boundary(bI)) bI += 1 else bI = 0
      }
    }
    if (bFound) chunk.drop(cI + 1)
    else Chunk.empty[Byte]
  }

  private def doMultiPart_scanAndTakeBeforeBoundary(
      chunk: Chunk[Byte],
      boundary: String
  ): (Boolean, Chunk[Byte], Chunk[Byte]) = {
    var bI                  = 0
    var cI                  = 0
    var bFound              = false
    var bFinalBoundaryFound = false
    breakable {
      for (c <- chunk) {
        cI += 1
        if (bI >= boundary.length()) {
          bFound = true
          break
        }
        if (c == boundary(bI)) bI += 1 else bI = 0
      }
    }
    if (bFound) (true, chunk.take(cI - boundary.length() - 3), chunk.drop(cI + 1)) // drop all 2: { \n, \r }
    else (false, chunk, Chunk.empty[Byte])                                         // false - no term, whole chunk
  }

  def multiPartPipe(boundary: String): ZPipeline[Any, Throwable, Byte, Headers | Chunk[Byte]] = {

    def multi_part_process_data_chunks(
        chunk: Chunk[Byte],
        boundary: String
    ): ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Headers | Chunk[Byte]], Any] = {
      val (stop, data_chunk, leftOver) = doMultiPart_scanAndTakeBeforeBoundary(chunk, boundary)
      if (stop == false) ZChannel.write(Chunk.single(data_chunk)) *> go4_data(Chunk.empty[Byte], boundary)
      else if (data_chunk.isEmpty) go4(Headers(), leftOver, boundary, true)
      else ZChannel.write(Chunk.single(data_chunk)) *> go4(Headers(), leftOver, boundary, true)
    }

    def multi_part_process_headers(
        h: Headers,
        chunk0: Chunk[Byte],
        boundary: String,
        hdrCont: Boolean
    ): ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Headers | Chunk[Byte]], Any] = {
      val chunk                 = if (hdrCont) chunk0 else doMultiPart_scanAndDropBoundary(chunk0, boundary)
      val (done, hdr, leftOver) = parseMPHeaders(h, chunk, boundary)
      if (hdr.tbl.isEmpty) ZChannel.succeed(true) // go4(h, Chunk.empty[Byte], boundary, hdrCont = true)
      else if (done) ZChannel.write(Chunk.single(hdr)) *> go4_data(leftOver, boundary)
      else go4(h, Chunk.empty[Byte], boundary, hdrCont = true)
    }

    def go4_data(
        carryOver: Chunk[Byte],
        boundary: String
    ): ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Headers | Chunk[Byte]], Any] = {
      ZChannel.readWith(
        (chunk1: Chunk[Byte]) => {
          val chunk = carryOver ++ chunk1
          multi_part_process_data_chunks(chunk, boundary)
        },
        (err: Exception) => ZChannel.fail(err),
        (done: Any) => {
          if (carryOver.isEmpty) ZChannel.succeed(true)
          else
            multi_part_process_data_chunks(carryOver, boundary) *> ZChannel.succeed(true)
        }
      )
    }

    def go4(
        h: Headers,
        carryOver: Chunk[Byte],
        boundary: String,
        hdrCont: Boolean = false
    ): ZChannel[Any, Exception, Chunk[Byte], Any, Exception, Chunk[Headers | Chunk[Byte]], Any] = {
      ZChannel.readWith(
        (chunk1: Chunk[Byte]) => {
          val chunk0 = carryOver ++ chunk1
          multi_part_process_headers(h, chunk0, boundary, hdrCont)
        },
        (err: Exception) => ZChannel.fail(err),
        (done: Any) => multi_part_process_headers(h, carryOver, boundary, hdrCont) *> ZChannel.succeed(true)
      )
    }

    ZPipeline.fromChannel(go4(Headers(), Chunk.empty[Byte], boundary))
  }

  private def stripQ(str: String) =
    str.stripPrefix("\"").stripSuffix("\"")

  def stream(req: Request): ZIO[Any, Throwable, ZStream[Any, Throwable, Headers | Chunk[Byte]]] =
    for {
      contType <- ZIO.succeed(req.contentType.toString)
      _ <- ZIO
        .fail(new Exception("multipart/form-data content type is missing"))
        .when(contType.toLowerCase().startsWith("multipart/form-data") == false)

      boundary <- ZIO.attempt(extractBoundaryFromMultipart(contType))

      mpStream = req.stream.flatMap(c => ZStream.fromChunk(c)).via(multiPartPipe(boundary))

    } yield (mpStream)

  def writeAll(req: Request, folderPath: String): Task[Unit] =
    for {
      contType <- ZIO.succeed(req.contentType.toString)
      _ <- ZIO
        .fail(new Exception("multipart/form-data content type is missing"))
        .when(contType.toLowerCase().startsWith("multipart/form-data") == false)

      boundary <- ZIO.attempt(extractBoundaryFromMultipart(contType))
      fileRef  <- Ref.make[FileOutputStream](null)

      mpStream = req.stream.flatMap(c => ZStream.fromChunk(c)).via(multiPartPipe(boundary))

      s0 <- mpStream.foreach {
        // each time we have headers we close and create FileOutputStream with file name from headers
        case h: Headers =>
          for {
            _ <- ZIO
              .attempt {
                Map.from(
                  h.get("Content-Disposition")
                    .get
                    .split(";")
                    .map(_.split("="))
                    .map(v1 => if (v1.length > 1) (v1(0).trim(), stripQ(v1(1).trim())) else (v1(0), ""))
                )("filename")
              }
              .tap(fname =>
                fileRef.get.map(fos => if (fos != null)(fos.close())) *> ZIO
                  .attempt(FileOutputStream(folderPath + fname))
                  .flatMap(fos =>
                    fileRef.set(
                      fos
                    )
                  )
              )
              .tap(fileName => ZIO.logInfo(s"HTTP multipart request: processing file $fileName"))
          } yield ()
        case b: Chunk[Byte] =>
          for {
            fos <- fileRef.get
            _   <- ZIO.attemptBlocking(fos.write(b.toArray))
          } yield ()
      }

    } yield ()
}
