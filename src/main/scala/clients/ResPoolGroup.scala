package zhttp.clients

import zio.ZLayer
import zio.ZEnv
import zio.Has
import zio.ZIO
import zio.Chunk
import zio.Tag

import zio.Queue
import zio.ZQueue

import zhttp.MyLogging
import zhttp.MyLogging.MyLogging
import zio.Runtime._
import zhttp.LogLevel

object ResPoolGroup {

  //ResPool descriptor
  case class RPD[R]( createRes : () => R, closeRes : (R) => Unit, name: String )


  type ResRec[R]       = ResPool.ResRec[R]
  type ResPoolGroup[R] = Has[ResPoolGroup.Service[R]]

  trait Service[R] {
    def acquire(pool_id: String): ZIO[ZEnv with MyLogging, Throwable, R]
    def release(pool_id: String, res: R): ZIO[ZEnv with MyLogging, Throwable, Unit]
  }

  //cleanup with sequence
  private def cleanup2[R]( pools: Chunk[( RPD[R], Queue[ResRec[R]])] ) = {

    val chunkOfZIOwork = pools.map { q =>
      for {
        logSvc <- MyLogging.logService
        all_conections <- q._2.takeAll
        UnitOfWork     <- ZIO.effect(all_conections.foreach(rec => { 
          q._1.closeRes( rec.res ) 
          val pool_id = q._1.name
          zio.Runtime.default.unsafeRun( 
                       logSvc.log( "console", LogLevel.Trace, s"ResPoolGroup: $pool_id - closing resource on shutdown" ))
        }))
      } yield (UnitOfWork)

    }
    ZIO.collectAll(chunkOfZIOwork).catchAll { e =>
      ZIO.unit
    }
  }

  //cleanup with fold
  private def cleanup[R]( pools: Chunk[( RPD[R], Queue[ResRec[R]])] ) =
    pools.foldLeft(ZIO.unit)((z, p) => {
      val T = p._2.takeAll.map { list =>
        list.foreach(rec => { 
          p._1.closeRes( rec.res ) 
        })
      }
      z *> T
    })

  private def shutdownAll[R](connections: Chunk[(RPD[R], Queue[ResRec[R]])]) =
    ZIO.effectTotal(connections.foreach { _._2.shutdown })

  def acquire[R](pool_id: String)(implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPoolGroup[R] with MyLogging](cpool => cpool.get[ResPoolGroup.Service[R]].acquire(pool_id))

  def release[R](pool_id: String, r: R)(implicit tagged: Tag[R]) =
    ZIO.accessM[ZEnv with ResPoolGroup[R] with MyLogging](cpool => cpool.get[ResPoolGroup.Service[R]].release(pool_id, r))

  /////////////////////////////////  
  def make[R]( rpd : RPD[R]*  )(
    implicit tagged: Tag[R]
  ) : ZLayer[zio.ZEnv with MyLogging.MyLogging,Nothing,Has[ResPoolGroup.Service[R]]] = {

    val chunkOfZIOQueues = Chunk.fromArray( rpd.toArray).map { pool_id =>
      (pool_id, ZQueue.unbounded[ResRec[R]])
    }

    //traverse
    val zioChunkOfQueues = ZIO.collect(chunkOfZIOQueues)(a => a._2.map(q => (a._1, q)))

    //managed Chunk of Queues
    val managedQueues = zioChunkOfQueues.toManaged(chunk => cleanup2( chunk ) *> shutdownAll(chunk))

    val oneServicebyNameForAllQueues = managedQueues.map { queues =>
      new Service[R] {

        def acquire(pool_id: String) =
          queues.find(_._1.name == pool_id) match {
            case Some(q) => ResPool.acquire_wrap( q._1.name, q._2, q._1.createRes, q._1.closeRes )
            case None    => ZIO.fail(new java.util.NoSuchElementException(s"ResPoolGroup pool_id: $pool_id not found"))
          }

        def release(pool_id: String, res: R) = {
          val q = queues.find(_._1.name == pool_id).get._2 //TODO - better exception on Option.get
          q.offer(ResPool.ResRec(res, new java.util.Date().getTime)).unit
        }
      }
    }
    oneServicebyNameForAllQueues.toLayer[ResPoolGroup.Service[R]]

  }

}
