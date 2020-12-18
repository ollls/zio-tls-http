package zhttp.clients

import zio.ZLayer
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

  var TIME_TO_LIVE = 1000 * 10 //  10 sec

  case class ResRec[R](res: R, timeToLive: Long = 0L)

  trait Service[R] {
    def acquire: ZIO[zio.ZEnv with MyLogging,Throwable,R]
    def release(res: R): ZIO[ZEnv with MyLogging, Throwable, Unit]
  }

  def acquire[R](implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPool[R] with MyLogging](cpool => cpool.get[ResPool.Service[R]].acquire)

  def release[R](r: R)(implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPool[R] with MyLogging](cpool => cpool.get[ResPool.Service[R]].release(r))

  private def cleanup[R](connections: zio.Queue[ResRec[R]], closeResource: (R) => Unit) =
    connections.takeAll.map { list =>
      list.foreach(rec => closeResource(rec.res))
    }

  private def cleanupM[R](connections: zio.Queue[ResRec[R]], closeResource: (R) => ZIO[ ZEnv, Exception, Unit] ) =
  {
     val T = for {
      list <- connections.takeAll
      units     <- ZIO.collectAll( list.map( 

        rec => closeResource(rec.res) *> 
        MyLogging.log( "console", LogLevel.Trace, s"ResPoolM: closing resource on shutdown" )
         )  )

     } yield( units ) 

     val TT = T *> ZIO.unit
    
     TT.catchAll( e => ZIO.unit )
  }

  def makeService[R](q: zio.Queue[ResRec[R]], createResource: () => R, closeResource: (R) => Unit) =
    new Service[R] {

      def acquire =
        for {
          optR <- q.poll.repeatWhile { or =>
                   or.isDefined &&
                   or.exists(
                     r =>
                       if (new java.util.Date().getTime() - r.timeToLive > TIME_TO_LIVE) {
                         closeResource(r.res); true
                       } else false
                   )
                 }
          resource <- if (optR.isDefined) IO.succeed(optR.map(_.res).get) else effectBlocking(createResource())

        } yield (resource)

      def release(res: R) = q.offer(ResRec(res, new java.util.Date().getTime)).unit
    }


  private[clients] def acquire_wrapM[R](
    pool_id : String,
    q: zio.Queue[ResRec[R]],
    createResource: () => ZIO[ ZEnv, Exception, R],
    closeResource: (R) => ZIO[ ZEnv, Exception, Unit],
  ) =
    for {
      logSvc <- MyLogging.logService
      optR   <- q.poll.repeatWhile { or =>
               or.isDefined && or.exists(
                 r =>
                   if (new java.util.Date().getTime() - r.timeToLive > TIME_TO_LIVE) {
                     Runtime.default.unsafeRun( 
                       closeResource(r.res) *>
                       logSvc.log( "console", LogLevel.Trace, s"ResPoolM: $pool_id - closing expired resource" )); true
                   } else false
               )
             }
      resource <- if (optR.isDefined) IO.succeed(optR.map(_.res).get) 
                  else {                    
                        MyLogging.log( "console", LogLevel.Trace, s"ResPoolM: $pool_id - create new resource" ) *>
                        createResource()
                  }        

    } yield (resource)
  

  private[clients] def acquire_wrap[R](
    pool_id : String,
    q: zio.Queue[ResRec[R]],
    createResource: () => R,
    closeResource: (R) => Unit
  ) =
    for {
      logSvc <- MyLogging.logService
      optR   <- q.poll.repeatWhile { or =>
               or.isDefined && or.exists(
                 r =>
                   if (new java.util.Date().getTime() - r.timeToLive > TIME_TO_LIVE) {
                     closeResource(r.res);
                     Runtime.default.unsafeRun( 
                       logSvc.log( "console", LogLevel.Trace, s"ResPool: $pool_id - closing expired resource" )); true
                   } else false
               )
             }
      resource <- if (optR.isDefined) IO.succeed(optR.map(_.res).get) 
                  else {                    
                        MyLogging.log( "console", LogLevel.Trace, s"ResPool: $pool_id - create new resource" ) *>
                        effectBlocking(createResource()) 
                  }        

    } yield (resource)

  def make[R](createResource: () => R, closeResource: (R) => Unit)(
    implicit tagged: Tag[R]
  ) = {
    val managedObj = ZQueue.unbounded[ResRec[R]].toManaged(q => { cleanup(q, closeResource) *> q.shutdown }).map { q =>
      new Service[R] {
        def acquire         = acquire_wrap( "default", q, createResource, closeResource)
        def release(res: R) = q.offer(ResRec(res, new java.util.Date().getTime)).unit
      }
    }
    managedObj.toLayer[ResPool.Service[R]]

  }

    def makeM[R]( 
        createResource: () => ZIO[ ZEnv, Exception, R],
        closeResource: (R) => ZIO[ ZEnv, Exception, Unit]
      )(
    implicit tagged: Tag[R]
  ) = {
    val managedObj = ZQueue.unbounded[ResRec[R]].toManaged(q => { cleanupM(q, closeResource ) *> q.shutdown }).map { q =>
      new Service[R] {
        def acquire         = acquire_wrapM( "default", q, createResource, closeResource)
        def release(res: R) = q.offer(ResRec(res, new java.util.Date().getTime)).unit
      }
    }
    managedObj.toLayer[ResPool.Service[R]]

  }

}
