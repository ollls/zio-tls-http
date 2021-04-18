package zhttp.clients

import zio.ZEnv
import zio.Has
import zio.ZQueue
import zio.ZIO
import zio.IO
import zio.blocking.effectBlocking
import zhttp.MyLogging.MyLogging
import zhttp.MyLogging

import zio.Tag
import zhttp.LogLevel

import zio.Runtime

object ResPool {

  type ResPool[R] = Has[ResPool.Service[R]]

  case class ResRec[R](res: R, timeToLive: Long = 0L)

  trait Service[R] {
    def acquire: ZIO[zio.ZEnv with MyLogging, Throwable, R]
    def release(res: R): ZIO[ZEnv with MyLogging, Nothing, Unit]
  }

  def acquire[R](implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPool[R] with MyLogging](cpool => cpool.get[ResPool.Service[R]].acquire)

  def release[R](r: R)(implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPool[R] with MyLogging](cpool => cpool.get[ResPool.Service[R]].release(r))

  private def cleanup[R](connections: zio.Queue[ResRec[R]], closeResource: (R) => Unit) =
    connections.takeAll.map { list =>
      list.foreach(rec => closeResource(rec.res))
    }

  private def layerName[R](implicit tagged: Tag[R]): String = {
    val vt = tagged.tag.shortName
    s"ResPool[$vt]"
  }

  private def layerNameM[R](implicit tagged: Tag[R]): String = {
    val vt = tagged.tag.shortName
    s"ResPoolM[$vt]"
  }

  private def cleanupM[R](connections: zio.Queue[ResRec[R]], closeResource: (R) => ZIO[ZEnv, Exception, Unit])(
    implicit tagged: Tag[R]
  ) = {
    val T = for {
      list <- connections.takeAll
      units <- ZIO.collectAll(
                list.map(
                  rec =>
                    closeResource(rec.res) *>
                      MyLogging.log("console", LogLevel.Debug, layerNameM[R] + ": closing resource on shutdown")
                )
              )

    } yield (units)

    val TT = T *> ZIO.unit

    TT.catchAll(e => ZIO.unit)
  }

  private[clients] def acquire_wrapM[R](
    timeToLiveMs: Int,
    pool_id: String,
    q: zio.Queue[ResRec[R]],
    createResource: () => ZIO[ZEnv, Exception, R],
    closeResource: (R) => ZIO[ZEnv, Exception, Unit]
  )(implicit tagged: Tag[R]) =
    for {
      logSvc <- MyLogging.logService
      optR <- q.poll.repeatWhile { or =>
               or.isDefined && or.exists(
                 r =>
                   if (new java.util.Date().getTime() - r.timeToLive > timeToLiveMs) {
                     Runtime.default.unsafeRun(
                       closeResource(r.res) *>
                         logSvc.log("console", LogLevel.Debug, layerNameM[R] + s": $pool_id - closing expired resource")
                     ); true
                   } else false
               )
             }
      resource <- if (optR.isDefined) IO.succeed(optR.map(_.res).get)
                 else {
                   MyLogging.log("console", LogLevel.Debug, layerNameM[R] + s": $pool_id - create new resource") *>
                     createResource()
                 }

    } yield (resource)

  private[clients] def acquire_wrap[R](
    timeToLiveMs: Int,
    pool_id: String,
    q: zio.Queue[ResRec[R]],
    createResource: () => R,
    closeResource: (R) => Unit
  )(implicit tagged: Tag[R]) =
    for {
      logSvc <- MyLogging.logService
      optR <- q.poll.repeatWhile { or =>
               or.isDefined && or.exists(
                 r =>
                   if (new java.util.Date().getTime() - r.timeToLive > timeToLiveMs) {
                     closeResource(r.res);
                     Runtime.default.unsafeRun(
                       logSvc.log("console", LogLevel.Debug, layerName[R] + s": $pool_id - closing expired resource")
                     ); true
                   } else false
               )
             }
      resource <- if (optR.isDefined) IO.succeed(optR.map(_.res).get)
                 else {
                   MyLogging.log("console", LogLevel.Debug, layerName[R] + s": $pool_id - create new resource") *>
                     effectBlocking(createResource())
                 }

    } yield (resource)

  def make[R](timeToLiveMs: Int, createResource: () => R, closeResource: (R) => Unit)(
    implicit tagged: Tag[R]
  ) = {
    val managedObj = ZQueue.unbounded[ResRec[R]].toManaged(q => { cleanup(q, closeResource) *> q.shutdown }).map { q =>
      new Service[R] {
        def acquire         = acquire_wrap(timeToLiveMs, "default", q, createResource, closeResource)
        def release(res: R) = q.offer(ResRec(res, new java.util.Date().getTime)).unit
      }
    }
    managedObj.toLayer[ResPool.Service[R]]

  }

  def makeM[R](
    timeToLiveMs: Int,
    createResource: () => ZIO[ZEnv, Exception, R],
    closeResource: (R) => ZIO[ZEnv, Exception, Unit]
  )(
    implicit tagged: Tag[R]
  ) = {
    val managedObj = ZQueue.unbounded[ResRec[R]].toManaged(q => { cleanupM(q, closeResource) *> q.shutdown }).map { q =>
      new Service[R] {
        def acquire         = acquire_wrapM(timeToLiveMs, "default", q, createResource, closeResource)
        def release(res: R) = q.offer(ResRec(res, new java.util.Date().getTime)).unit
      }
    }
    managedObj.toLayer[ResPool.Service[R]]

  }

}
