package zhttp.clients

import zio.ZLayer
import zio.ZEnv
import zio.Has
import zio.ZQueue
import zio.ZIO
import zio.IO
import zio.blocking.effectBlocking
import scala.annotation.tailrec
import com.unboundid.ldap.sdk.LDAPConnection
import zhttp.MyLogging.MyLogging
import zhttp.MyLogging

import zio.Tag
import zhttp.LogLevel

import zio.Runtime

object ResPool {

  type ResPool[R] = Has[ResPool.Service[R]]

  val TIME_TO_LIVE = 1000 * 10 //  10 sec

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

}
