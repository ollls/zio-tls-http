package zhttp

import zio.Has
import zio.ZLayer
import zio.ZQueue
import zio.UIO

import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.ByteBuffer

import scala.collection.immutable.ListMap
import zio.clock.{ currentDateTime }
import scala.io.AnsiColor
import scala.io.AnsiColor._

import zio.{ IO, ZEnv, ZIO }
import zio.blocking._

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.nio.file.Files
import java.time.OffsetDateTime

object MyLogging {

  type MyLogging = Has[Service]

  val FILE_TS_FMT    = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
  val REL_LOG_FOLDER = "logs/"

  private var MAX_LOG_FILE_SIZE       = 1024 * 1024 * 1 //1M
  private var MAX_NUMBER_ROTATED_LOGS = 5

  var PRINT_CONSOLE           = true

  class LogRec(logName: String, var log: FileChannel, val lvl: LogLevel) {

    private def critical_error_message(log_msg: String, lvl: LogLevel) = {
      val TS = withColor(
        AnsiColor.GREEN,
        LogDatetimeFormatter.humanReadableDateTimeFormatter.format(java.time.OffsetDateTime.now())
      )
      val MSG          = withColor(AnsiColor.YELLOW, log_msg)
      val LVL          = withColor(colorFromLogLevel(lvl), lvl.render)
      val console_line = s"$TS [$LVL] $MSG"
      println(console_line)
    }

    private def removeOldLogs = {
      val dir = FileSystems.getDefault().getPath(REL_LOG_FOLDER)

      Files
        .list(dir)
        .filter(c => !c.endsWith(logName + ".log"))
        .filter(c => c.getName(1).toString.startsWith(logName))
        .sorted(java.util.Comparator.reverseOrder())
        .skip(MAX_NUMBER_ROTATED_LOGS)
        .forEach(c => c.toFile.delete)

    }

    private def reOpenLogFile =
      log = FileChannel.open(
        FileSystems.getDefault().getPath(REL_LOG_FOLDER, logName + ".log"),
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND,
        java.nio.file.StandardOpenOption.SYNC
      )

    private def archiveLogFile = {
      val srcFile = FileSystems.getDefault().getPath(REL_LOG_FOLDER, logName + ".log").toFile()
      val archFile = FileSystems
        .getDefault()
        .getPath(REL_LOG_FOLDER, logName + "." + LocalDateTime.now().format(FILE_TS_FMT) + ".log")
        .toFile()
      srcFile.renameTo(archFile)
    }

    def write_rotate(line: String) =
      try {
        log.write(ByteBuffer.wrap(line.getBytes()))
        if (log.size() > MAX_LOG_FILE_SIZE) {
          log.close()
          archiveLogFile
          removeOldLogs
          reOpenLogFile
        }
      } catch {
        case e: Exception =>
          println(critical_error_message("CRITICAL: CAN NOT WRITE LOGS " + e.toString, LogLevel.Error))
      }

  }

  private def withColor(color: String, s: String): String = s"$color$s$RESET"

  def colorFromLogLevel(lvl: LogLevel): String =
    lvl match {
      case LogLevel.Error => AnsiColor.RED
      case LogLevel.Info  => AnsiColor.WHITE
      case LogLevel.Trace => AnsiColor.MAGENTA
      case LogLevel.Debug => AnsiColor.CYAN

      case _ => AnsiColor.WHITE
    }

  trait Service {
    def log(logName: String, lvl: LogLevel, msg: String): ZIO[ZEnv, Exception, Unit]
    def listLogs : UIO[Iterator[(String, LogRec)]]
    def shutdown : ZIO[ZEnv, Exception, Unit]
  }

  def log(name: String, lvl: LogLevel, msg: String): ZIO[ZEnv with MyLogging, Exception, Unit] =
    ZIO.accessM[ZEnv with MyLogging](logenv => logenv.get[MyLogging.Service].log(name, lvl, msg))

  def logService: ZIO[ZEnv with MyLogging, Exception, MyLogging.Service] =
    ZIO.access[ZEnv with MyLogging](logenv => logenv.get[MyLogging.Service])

  def info(name: String, msg: String): ZIO[ZEnv with MyLogging, Exception, Unit] =
    log(name, LogLevel.Info, msg)

  def debug(name: String, msg: String): ZIO[ZEnv with MyLogging, Exception, Unit] =
    log(name, LogLevel.Debug, msg)

  def error(name: String, msg: String): ZIO[ZEnv with MyLogging, Exception, Unit] =
    log(name, LogLevel.Error, msg)

  def trace(name: String, msg: String): ZIO[ZEnv with MyLogging, Exception, Unit] =
    log(name, LogLevel.Trace, msg)

  def warn(name: String, msg: String): ZIO[ZEnv with MyLogging, Exception, Unit] =
    log(name, LogLevel.Warn, msg)

  private def write_logs(
    logs: ListMap[String, LogRec],
    log_name: String,
    lvl: LogLevel,
    log_msg: String,
    date: OffsetDateTime,
    fiberId: zio.Fiber.Id
  ): ZIO[ZEnv, Throwable, Unit] =
    for {
      ts <- IO.effectTotal(LogDatetimeFormatter.humanReadableDateTimeFormatter.format(date))
      _ <- effectBlocking(logs.get(log_name).foreach { logRec =>
            {
              if (lvl >= logRec.lvl) {
                val strLvl   = lvl.render
                val fiberNum = fiberId.seqNumber
                val line     = s"$ts [$strLvl] [$fiberNum] $log_msg\n"
                if (log_name == "console" && PRINT_CONSOLE == true) {
                  val TS           = withColor(AnsiColor.GREEN, ts)
                  val MSG          = withColor(AnsiColor.YELLOW, log_msg)
                  val LVL          = withColor(colorFromLogLevel(lvl), lvl.render)
                  val FIBER        = withColor(AnsiColor.GREEN, fiberNum.toString)
                  val console_line = s"$TS [$LVL] [$FIBER] $MSG"
                  println(console_line)
                }
                logRec.write_rotate(line)
              }
            }
          })
    } yield ()

  private def open_logs(log_names: Seq[(String, LogLevel)]): ZIO[ZEnv, Throwable, ListMap[String, LogRec]] =
    for {
      //_    <- zio.console.putStrLn( "ZIO-TLS-HTTP started, log files in: " + REL_LOG_FOLDER + log_names.mkString( ",") )
      logs <- effectBlocking(ListMap[String, LogRec]())
      result <- IO.effect {
                 // ensure log folder exists
                 val logPath = FileSystems.getDefault().getPath(REL_LOG_FOLDER)
                 Files.createDirectories(logPath)
                 log_names.foldLeft(logs)(
                   (logs, name) =>
                     logs + (name._1 -> new LogRec(
                       name._1,
                       FileChannel.open(
                         logPath.resolve(name._1 + ".log"),
                         java.nio.file.StandardOpenOption.CREATE,
                         java.nio.file.StandardOpenOption.APPEND,
                         java.nio.file.StandardOpenOption.SYNC
                       ),
                       name._2
                     ))
                 )
               }
    } yield (result)

  private def close_logs(logs: ListMap[String, LogRec]): ZIO[ZEnv, Nothing, Unit] =
    effectBlocking(logs.foreach(rec => rec._2.log.close())).catchAll(_ => IO.unit)

  def make(
    maxLogSize: Int,
    maxLogFiles: Int,
    log_names: (String, LogLevel)*
  ): ZLayer[ZEnv, Throwable, Has[MyLogging.Service]] = {
    MAX_LOG_FILE_SIZE = maxLogSize
    MAX_NUMBER_ROTATED_LOGS = maxLogFiles

    make(log_names: _*)

  }

  def make(log_names: (String, LogLevel)*): ZLayer[ZEnv, Throwable, Has[MyLogging.Service]] = {
    val managedObj = open_logs(log_names).toManaged(close_logs).flatMap { logs =>
      ZQueue
      //with unbounded queue you won't lose a single record, but with stress test it grows up to 10-15 min delay and more
      //here for perfomance reasons we use dropping queue, some messages under stress load will be lost, once it exceeds a queue
      //you always can switch off access log and avoid issue entirely
      //.unbounded[(String, LogLevel, String, OffsetDateTime)]   //<--- uncomment for unlimited, no loss queue
        .dropping[(String, LogLevel, String, OffsetDateTime, zio.Fiber.Id)](5000)
        .tap(q => q.take.flatMap(msg => write_logs(logs, msg._1, msg._2, msg._3, msg._4, msg._5)).repeatUntilM( _ => q.isShutdown ).forkDaemon)
        .toManaged(c => c.shutdown)
        .map(
          q =>
            new Service {

                def listLogs = ZIO.effectTotal( logs.iterator )

                def shutdown : ZIO[ZEnv, Exception, Unit] = q.shutdown

                def log(log: String, lvl: LogLevel, msg: String): ZIO[ZEnv, Exception, Unit] =
                for {
                  fiberId <- ZIO.fiberId
                  time    <- currentDateTime.orDie
                  _ <- logs.get(log) match {
                        case None => IO.unit
                        case Some(logRec) =>
                          if (logRec.lvl <= lvl) {
                            q.offer((log, lvl, msg, time, fiberId)).unit
                          } else IO.unit
                      }
                } yield ()
            }
        )

    }

    managedObj.toLayer[MyLogging.Service]

  }

}

object Logs {

  def log_access(req: Request, status: StatusCode, bodySize: Int) = {
    val addr = Channel.remoteAddress(req.ch).flatMap {
      case None    => IO.effect("???")
      case Some(a) => IO.effect(a.toInetSocketAddress.hostString)
    }

    addr.flatMap { addrStr =>
      MyLogging.info(
        "access",
        addrStr + " " + "\""
          + req.method + " "
          + req.uri.toString + "\"" + " "
          + status.value + " " + bodySize
      ) *> //duplicate to console if trace
        MyLogging.trace(
          "console",
          addrStr + " " + "\""
            + req.method + " "
            + req.uri.toString + "\"" + " "
            + status.value + " " + bodySize
        )

    }
  }

}
