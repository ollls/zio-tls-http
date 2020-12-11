package zhttp.clients

import zio.ZLayer
import zio.ZEnv
import zio.Has
import zio.ZIO
import zio.Chunk
import zio.Tag

import zio.Queue
import zio.ZQueue

object ResPoolGroup {

  type ResRec[R]       = ResPool.ResRec[R]
  type ResPoolGroup[R] = Has[ResPoolGroup.Service[R]]

  trait Service[R] {
    def acquire(pool_id: String): ZIO[ZEnv with ResPoolGroup[R], Throwable, R]
    def release(pool_id: String, res: R): ZIO[ZEnv with ResPoolGroup[R], Throwable, Unit]
  }

  //cleanup with sequence
  private def cleanup2[R](connections: Chunk[(String, Queue[ResRec[R]])], closeResource: (R) => Unit) = {

    val chunkOfZIOwork = connections.map { q =>
      for {
        all_conections <- q._2.takeAll
        UnitOfWork     <- ZIO.effect(all_conections.foreach(rec => { closeResource(rec.res) }))
      } yield (UnitOfWork)

    }
    ZIO.collectAll(chunkOfZIOwork).catchAll { e =>
      ZIO.unit
    }
  }

  //cleanup with fold
  private def cleanup[R](connections: Chunk[(String, Queue[ResRec[R]])], closeResource: (R) => Unit) =
    connections.foldLeft(ZIO.unit)((z, p) => {
      val T = p._2.takeAll.map { list =>
        list.foreach(rec => { closeResource(rec.res) })
      }
      z *> T
    })

  private def shutdownAll[R](connections: Chunk[(String, Queue[ResRec[R]])]) =
    ZIO.effectTotal(connections.foreach { _._2.shutdown })

  def acquire[R](pool_id: String)(implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPoolGroup[R]](cpool => cpool.get[ResPoolGroup.Service[R]].acquire(pool_id))

  def release[R](pool_id: String, r: R)(implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPoolGroup[R]](cpool => cpool.get[ResPoolGroup.Service[R]].release(pool_id, r))

  def make[R](createResource: () => R, closeResource: (R) => Unit, names: String*)(
    implicit tagged: Tag[R]
  ): ZLayer[ZEnv, Nothing, ResPoolGroup[R]] = {

    val chunkOfZIOQueues = Chunk.fromArray(names.toArray).map { pool_id =>
      (pool_id, ZQueue.unbounded[ResRec[R]])
    }

    //traverse
    val zioChunkOfQueues = ZIO.collect(chunkOfZIOQueues)(a => a._2.map(q => (a._1, q)))

    //managed Chunk of Queues
    val managedQueues = zioChunkOfQueues.toManaged(chunk => cleanup2(chunk, closeResource) *> shutdownAll(chunk))

    val oneServicebyNameForAllQueues = managedQueues.map { queues =>
      new Service[R] {

        def acquire(pool_id: String): ZIO[ZEnv with ResPoolGroup[R], Throwable, R] =
          queues.find(_._1 == pool_id) match {
            case Some(q) => ResPool.acquire_wrap(q._2, createResource, closeResource)
            case None    => ZIO.fail(new java.util.NoSuchElementException(s"ResPoolGroup pool_id: $pool_id not found"))
          }

        def release(pool_id: String, res: R): ZIO[ZEnv with ResPoolGroup[R], Throwable, Unit] = {
          val q = queues.find(_._1 == pool_id).get._2 //TODO - better exception on Option.get
          q.offer(ResPool.ResRec(res, new java.util.Date().getTime)).unit
        }
      }
    }
    oneServicebyNameForAllQueues.toLayer[ResPoolGroup.Service[R]]

  }

}
