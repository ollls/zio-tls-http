package zhttp

import zio.Has
import zio.ZLayer
import zio.ZQueue
import java.io.FileWriter
import scala.collection.immutable.ListMap
import zio.clock.{ currentDateTime }
import scala.io.AnsiColor
import scala.io.AnsiColor._

import zio.{ IO, ZEnv, ZIO }
import zio.blocking._

object MyLogging {

  val  REL_LOG_FOLDER = "logs/"

  case class LogRec(logName: String, log: FileWriter, lvl: LogLevel)

  type MyLogging = Has[Service]

  private def withColor(color: String, s: String): String = s"$color$s$RESET"

  def colorFromLogLevel( lvl : LogLevel ) : String = 
  {
    lvl match {
      case LogLevel.Error =>  AnsiColor.RED
      case LogLevel.Info  =>  AnsiColor.WHITE
      case LogLevel.Trace =>  AnsiColor.CYAN
      case LogLevel.Debug =>  AnsiColor.MAGENTA
      
      case _              =>  AnsiColor.WHITE
    }   
  }

  trait Service {
    def log(logName: String, lvl: LogLevel, msg: String): ZIO[MyLogging, Throwable, Unit]
  }

  def log(name: String, lvl: LogLevel, msg: String): ZIO[MyLogging, Exception, Unit] =
    ZIO.accessM[MyLogging](logenv => logenv.get.log(name, lvl, msg)).refineToOrDie[Exception]

  def info(name: String, msg: String): ZIO[MyLogging, Exception, Unit] =
    log(name, LogLevel.Info, msg)

  def debug(name: String, msg: String): ZIO[MyLogging, Exception, Unit] =
    log(name, LogLevel.Debug, msg)

  def error(name: String, msg: String): ZIO[MyLogging, Exception, Unit] =
    log(name, LogLevel.Error, msg)

  def trace(name: String, msg: String): ZIO[MyLogging, Exception, Unit] =
    log(name, LogLevel.Trace, msg)

  def warn(name: String, msg: String): ZIO[MyLogging, Exception, Unit] =
    log(name, LogLevel.Warn, msg)

  private def write_logs(
    logs: ListMap[String, LogRec],
    log_name: String,
    lvl: LogLevel,
    log_msg: String
  ): ZIO[ZEnv, Throwable, Unit] =
    for {
      date <- currentDateTime.orDie
      ts   <- IO.effectTotal(LogDatetimeFormatter.humanReadableDateTimeFormatter.format(date))
      _ <- effectBlocking(logs.get(log_name).foreach { logRec =>
            {
              if (lvl >= logRec.lvl) {
                val strLvl = lvl.render
                val line = s"$ts [$strLvl] $log_msg\n"
                if (log_name == "console") {
                  val TS           = withColor(AnsiColor.GREEN, ts)
                  val MSG          = withColor(AnsiColor.YELLOW, log_msg)
                  val LVL          = withColor( colorFromLogLevel( lvl ), lvl.render )
                  val console_line = s"$TS [$LVL] $MSG"
                  println(console_line)
                }
                logRec.log.write(line); logRec.log.flush()
              }
            }
          })
    } yield ()

  private def open_logs(log_names: Seq[(String, LogLevel)]): ZIO[ZEnv, Throwable, ListMap[String, LogRec]] = {
    for {
      //_    <- zio.console.putStrLn( "ZIO-TLS-HTTP started, log files in: " + REL_LOG_FOLDER + log_names.mkString( ",") )  
      logs <- effectBlocking(ListMap[String, LogRec]())
      result <- IO.effect(
                 log_names.foldLeft(logs)(
                   (logs, name) => logs + (name._1 -> LogRec(name._1, new FileWriter( REL_LOG_FOLDER + name._1 + ".log", true), name._2))
                 )
               )          
    } yield (result)
  }

  private def close_logs(logs: ListMap[String, LogRec]): ZIO[ZEnv, Nothing, Unit] =
    effectBlocking(logs.foreach(rec => rec._2.log.close())).catchAll(_ => IO.unit)

  def make(log_names: (String, LogLevel)*): ZLayer[ZEnv, Throwable, Has[MyLogging.Service]] = {

    val managedObj = open_logs(log_names).toManaged(close_logs).flatMap { logs =>
      ZQueue
        //.unbounded[(String, LogLevel, String)]
        .dropping[(String, LogLevel, String)]( 1000 )
        .tap(q => q.take.flatMap(msg => write_logs(logs, msg._1, msg._2, msg._3)).forever.forkDaemon)
        .toManaged(c => { c.shutdown })
        .map(
          q =>
            new Service {
              override def log(log: String, lvl: LogLevel, msg: String): ZIO[MyLogging, Throwable, Unit] =
                q.offer((log, lvl, msg)).unit

            }
        )

    }

    managedObj.toLayer[MyLogging.Service]

  }

}

object Logs {

  def log_access(req: Request, status: StatusCode, bodySize: Int) = {
    val addr = req.ch.remoteAddress.flatMap {
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
