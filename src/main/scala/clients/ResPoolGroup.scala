package zhttp.clients

import zio.ZLayer
import zio.ZIO
import zio.Chunk
import zio.Tag
import zio.Queue


object ResPoolGroup {

  //ResPool descriptor
  case class RPD[R](createRes: () => R, closeRes: (R) => Unit, name: String)

  case class RPDM[R](
    createRes: () => ZIO[Any, Exception, R],
    closeRes: (R) => ZIO[Any, Exception, Unit],
    name: String
  )

  type ResRec[R]       = ResPool.ResRec[R]
  type ResPoolGroup[R] = ResPoolGroup.Service[R]

  trait Service[R] {
    def acquire(pool_id: String): ZIO[Any, Throwable, R]
    def release(pool_id: String, res: R): ZIO[Any, Throwable, Unit]
  }

  //cleanup with sequence
  private def cleanup2[R](pools: Chunk[(RPD[R], Queue[ResRec[R]])]) : ZIO[Any, Nothing, Matchable] = {

    val chunkOfZIOwork = pools.map { q =>
      for {
        all_conections <- q._2.takeAll
        UnitOfWork <- ZIO.attempt(all_conections.foreach(rec => {
                       q._1.closeRes(rec.res)
                       //val pool_id = q._1.name
                       //zio.Runtime.default.unsafeRunTask(
                        // logSvc.log("console", LogLevel.Trace, s"ResPoolGroup: $pool_id - closing resource on shutdown")
                       //)
                     }))
      } yield (UnitOfWork)

    }
    ZIO.collectAll(chunkOfZIOwork).catchAll { e =>
      ZIO.unit
    }
  }

  //cleanup with fold
  private def cleanup[R](pools: Chunk[(RPD[R], Queue[ResRec[R]])]) =
    pools.foldLeft(ZIO.unit)((z, p) => {
      val T = p._2.takeAll.map { list =>
        list.foreach(rec => {
          p._1.closeRes(rec.res)
        })
      }
      z *> T
    })

  ///////////////////
  private def cleanupM[R](pools: Chunk[(RPDM[R], Queue[ResRec[R]])]) = {

    val T = pools.map(r => {

      for {
        all_connections <- r._2.takeAll

        c <- ZIO.collectAll(all_connections.map {
              val pool_id = r._1.name
              c =>
                r._1.closeRes(c.res) *>
                  ZIO.logDebug(s"ResPoolGroup: $pool_id - closing resource on shutdown")
            })

      } yield ()
    })

    ZIO.collectAll(T).flatMap(c => ZIO.unit).catchAll(e => ZIO.unit)

  }

  private def shutdownAll[R](connections: Chunk[(RPD[R], Queue[ResRec[R]])]) =
    ZIO.succeed(connections.foreach { _._2.shutdown })

  private def shutdownAllM[R](connections: Chunk[(RPDM[R], Queue[ResRec[R]])]) =
    ZIO.succeed(connections.foreach { _._2.shutdown })

  def acquire[R](pool_id: String)(implicit tagged: Tag[R]) =
    ZIO.environmentWithZIO[ResPoolGroup[R]](cpool => cpool.get[ResPoolGroup.Service[R]].acquire(pool_id))

  def release[R](pool_id: String, r: R)(implicit tagged: Tag[R]) =
    ZIO.environmentWithZIO[ResPoolGroup[R]](
      cpool => cpool.get[ResPoolGroup.Service[R]].release(pool_id, r)
    )

  //////////////////////////////////
  def makeM[R](
    timeToLiveMs : Int,
    rpdm: RPDM[R]*
  )(implicit tagged: Tag[R]): ZLayer[Any, Nothing, ResPoolGroup.Service[R]] = {

    val chunkOfZIOQueues = Chunk.fromArray(rpdm.toArray).map { pool_id =>
      (pool_id, Queue.unbounded[ResRec[R]])
    }

    val managedQueues2 = ZIO.acquireRelease( ZIO.collect(chunkOfZIOQueues)(a => a._2.map(q => (a._1, q))) )( chunk => cleanupM(chunk) *> shutdownAllM(chunk) )

    val oneServicebyNameForAllQueues = managedQueues2.map { queues =>
      new Service[R] {

        def acquire(pool_id: String) =
          queues.find(_._1.name == pool_id) match {
            case Some(q) => ResPool.acquire_wrapM( timeToLiveMs, q._1.name, q._2, q._1.createRes, q._1.closeRes)
            case None    => ZIO.fail(new java.util.NoSuchElementException(s"ResPoolGroup pool_id: $pool_id not found"))
          }

        def release(pool_id: String, res: R) = {
          val q = queues.find(_._1.name == pool_id).get._2 //TODO - better exception on Option.get
          q.offer(ResPool.ResRec(res, new java.util.Date().getTime)).unit
        }
      }
    }
   
    ZLayer.scoped( oneServicebyNameForAllQueues )

  }

  /////////////////////////////////
  def make[R](
    timeToLiveMs : Int,
    rpd: RPD[R]*)(
    implicit tagged: Tag[R]
  ) : ZIO[zio.Clock & zio.Console & (zio.System & zio.Random ), Nothing, Service[R]]= {

    val chunkOfZIOQueues = Chunk.fromArray(rpd.toArray).map { pool_id =>
      (pool_id, Queue.unbounded[ResRec[R]])
    }

     val managedQueues2 = ZIO.acquireRelease( ZIO.collect(chunkOfZIOQueues)(a => a._2.map(q => (a._1, q))) )( chunk => cleanup2(chunk) *> shutdownAll(chunk) )

    val oneServicebyNameForAllQueues = managedQueues2.map { queues =>
      new Service[R] {

        def acquire(pool_id: String) =
          queues.find(_._1.name == pool_id) match {
            case Some(q) => ResPool.acquire_wrap( timeToLiveMs, q._1.name, q._2, q._1.createRes, q._1.closeRes)
            case None    => ZIO.fail(new java.util.NoSuchElementException(s"ResPoolGroup pool_id: $pool_id not found"))
          }

        def release(pool_id: String, res: R) = {
          val q = queues.find(_._1.name == pool_id).get._2 //TODO - better exception on Option.get
          q.offer(ResPool.ResRec(res, new java.util.Date().getTime)).unit
        }
      }
    }

    ZIO.scoped( oneServicebyNameForAllQueues )

  }

}
