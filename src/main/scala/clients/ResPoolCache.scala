package clients

import zio.ZIO
import zio.ZLayer
import zio.ZEnv
import zio.Has
import zhttp.MyLogging.MyLogging
import zhttp.clients.ResPool
import zio.Tag
import zio.ZManaged

object ResPoolCache {

  type ResPoolCache[K, V, R] = Has[ResPoolCache.Service[K, V, R]]

  trait Service[K, V, R] {
    def get(key: K): ZIO[zio.ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V]
  }

  def get[K, V, R](key: K)(implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]) =
    ZIO.accessM[ZEnv with ResPoolCache[K, V, R] with MyLogging](svc => svc.get[ResPoolCache.Service[K, V, R]].get(key))

  def make[K, V, R](updatef: (R, K) => ZIO[ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V])(
    implicit tagged: Tag[R],
    tagged1: Tag[K],
    tagged2: Tag[V]
  ): ZLayer[Has[ResPool.Service[R]], Nothing, Has[ResPoolCache.Service[K, V, R]]] =
    ZLayer.fromService[ResPool.Service[R], ResPoolCache.Service[K, V, R]](
      rp =>
        new Service[K, V, R] {
          def get(key: K): ZIO[zio.ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V] = 
              ZManaged.make( rp.acquire )( c =>  rp.release( c ).catchAll(  _ => ZIO.unit ) ).use( resource => updatef(resource, key) )
        }
    )

}
