package zhttp.clients

import zio.Queue
import zio.ZLayer
import zio.ZIO
import zio.IO
import zio.Tag
import zio.Runtime
import zio.ZIO.attemptBlocking

object ResPool {

  type ResPool[R] = ResPool.Service[R]

  case class ResRec[R](res: R, timeToLive: Long = 0L)

  trait Service[R] {
    def acquire: ZIO[Any, Throwable, R]
    def release(res: R): ZIO[Any, Nothing, Unit]
  }

  def acquire[R](implicit tagged: Tag[R]) =
    ZIO.environmentWithZIO[ResPool[R]](cpool => cpool.get[ResPool.Service[R]].acquire)

  def release[R](r: R)(implicit tagged: Tag[R]) =
    ZIO.environmentWithZIO[ResPool[R]](cpool => cpool.get[ResPool.Service[R]].release(r))

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

  private def cleanupM[R](connections: zio.Queue[ResRec[R]], closeResource: (R) => ZIO[Any, Exception, Unit])(
    implicit tagged: Tag[R]
  ) = {
    val T = for {
      list <- connections.takeAll
      units <- ZIO.collectAll(
                list.map(
                  rec =>
                    closeResource(rec.res) *>
                      ZIO.logDebug(layerNameM[R] + ": closing resource on shutdown")
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
    createResource: () => ZIO[Any, Exception, R],
    closeResource: (R) => ZIO[Any, Exception, Unit]
  )(implicit tagged: Tag[R]) =
    for {
      optR <- q.poll.repeatWhile { or =>
               or.isDefined && or.exists(
                 r =>
                   if (new java.util.Date().getTime() - r.timeToLive > timeToLiveMs) {
                     closeResource(r.res) 
                     //Runtime.default.unsafeRun(
                     //  closeResource(r.res) *>
                     //    logSvc.log("console", LogLevel.Debug, layerNameM[R] + s": $pool_id - closing expired resource")
                     //);
                      true
                   } else false
               )
             }
      resource <- if (optR.isDefined) ZIO.succeed(optR.map(_.res).get)
                 else {
                   ZIO.logDebug(layerNameM[R] + s": $pool_id - create new resource") *>
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
      optR <- q.poll.repeatWhile { or =>
               or.isDefined && or.exists(
                 r =>
                   if (new java.util.Date().getTime() - r.timeToLive > timeToLiveMs) {
                     closeResource(r.res);
                     //Runtime.default.unsafeRun[Exception, Unit](
                     // logSvc.log("console", LogLevel.Debug, layerName[R] + s": $pool_id - closing expired resource")
                     //); 
                     true
                   } else false
               )
             }
      resource <- if (optR.isDefined) ZIO.succeed(optR.map(_.res).get)
                 else {
                   ZIO.logDebug( layerName[R] + s": $pool_id - create new resource") *>
                     attemptBlocking(createResource())
                 }

    } yield (resource)


  def makeScoped[R](timeToLiveMs: Int, createResource: () => R, closeResource: (R) => Unit)( implicit tagged: Tag[R] ) = 
  {

      val obj = ZIO.acquireRelease( Queue.unbounded[ResRec[R]] )( q => { cleanup(q, closeResource) *> q.shutdown } ).map { q =>
        new Service[R] {
          def acquire         = acquire_wrap(timeToLiveMs, "default", q, createResource, closeResource)
          def release(res: R) = q.offer(ResRec(res, new java.util.Date().getTime)).unit
        }
      } 
      ZLayer.scoped( obj ) 
  }


  def makeScopedZIO[R](
    timeToLiveMs: Int,
    createResource: () => ZIO[Any, Exception, R],
    closeResource: (R) => ZIO[Any, Exception, Unit]
  )( implicit tagged: Tag[R] ) = 
  {
     val obj = ZIO.acquireRelease( Queue.unbounded[ResRec[R]] )( q => { cleanupM(q, closeResource) *> q.shutdown } ).map { q =>
      new Service[R] { 
        def acquire         = acquire_wrapM(timeToLiveMs, "default", q, createResource, closeResource)
        def release(res: R) = q.offer(ResRec(res, new java.util.Date().getTime)).unit
      } 
    }
      ZLayer.scoped( obj )  
  }


}
