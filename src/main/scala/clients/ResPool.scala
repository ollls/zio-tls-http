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
import zhttp.MyLogging

import zio.Tag

object ResPool {

  type ResPool[R] = Has[ResPool.Service[R]]

  val TIME_TO_LIVE = 1000 * 10 //  10 sec

  case class ResRec[R](res: R, timeToLive: Long = 0L)

  trait Service[R] {
    def acquire: ZIO[ZEnv with ResPool[R], Throwable, R]
    def release(res: R): ZIO[ZEnv with ResPool[R], Throwable, Unit]
  }

  def acquire[R](implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPool[R]](cpool => cpool.get[ResPool.Service[R]].acquire)

  def release[R](r: R)(implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPool[R]](cpool => cpool.get[ResPool.Service[R]].release(r))

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

  private[clients] def acquire_wrap[R](q: zio.Queue[ResRec[R]], createResource: () => R, closeResource: (R) => Unit) =
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

  def make[R](createResource: () => R, closeResource: (R) => Unit)(
    implicit tagged: Tag[R]
  ): ZLayer[ZEnv, Nothing, ResPool[R]] = {
    val managedObj = ZQueue.unbounded[ResRec[R]].toManaged(q => { cleanup(q, closeResource) *> q.shutdown }).map { q =>
      new Service[R] {
        def acquire         = acquire_wrap(q, createResource, closeResource)
        def release(res: R) = q.offer(ResRec(res, new java.util.Date().getTime)).unit
      }
    }
    managedObj.toLayer[ResPool.Service[R]]

  }

}
