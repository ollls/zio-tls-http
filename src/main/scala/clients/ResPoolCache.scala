package clients

import zio.ZIO
import zio.UIO
import zio.ZLayer
import zio.ZEnv
import zio.Has
import zhttp.MyLogging.MyLogging
import zhttp.clients.ResPool
import zio.Tag
import zio.ZManaged
import zio.ZQueue
import zio.Semaphore

import zhttp.clients.util.SkipList
import zhttp.clients.util.ValuePair

import zhttp.MyLogging.MyLogging
import zhttp.MyLogging
import zhttp.LogLevel

import scala.volatile
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

import java.lang.Runtime
import zio.blocking.Blocking

object ResPoolCache {

  val COMMON_FACTOR = 9

  class CacheEntry[T](@volatile var cached_val: T, @volatile var ts: Long = 0L) {
    //////////////////////////////////////////////////////////////
    def timeStampIt = ts = java.time.Instant.now().toEpochMilli();

    //////////////////////////////////////////////////////////////
    def isExpired(mills: Int): Boolean = {
      val now = java.time.Instant.now().toEpochMilli()
      if (now - ts > mills) true else false
    }
  }

  class LRUQEntry[K](val timestamp: Long, val key: K)(implicit ord: K => Ordered[K]) extends Ordered[LRUQEntry[K]] {

    override def compare(that: LRUQEntry[K]): Int =
      if (timestamp > that.timestamp) 1
      else if (timestamp < that.timestamp) -1
      else // if equals, compare by key
        {
          key.compare(that.key)
        }
  }

  class LRUListWithCounter[K] {
    private[clients] val lru_tbl = new SkipList[LRUQEntry[K]]
    private val lru_count        = new AtomicInteger(0)
    lru_tbl.FACTOR = COMMON_FACTOR

    def add(e: LRUQEntry[K]) = {
      val b = lru_tbl.add(e)
      if (b) lru_count.incrementAndGet()
      b
    }

    def remove(e: LRUQEntry[K]) = {
      val b = lru_tbl.remove(e)
      if (b) lru_count.decrementAndGet()
      b
    }

    def count = lru_count.get

    def count2 = lru_tbl.count()

    def head = lru_tbl.head

    def stat(sb: StringBuilder) = lru_tbl.print(sb)

    def factor = lru_tbl.FACTOR

  }

  type ResPoolCache[K, V, R] = Has[ResPoolCache.Service[K, V, R]]

  trait Service[K, V, R] {
    def get(key: K): ZIO[zio.ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V]

    def info: ZIO[ZEnv, Throwable, String]

    def doFreeSpace: ZIO[Blocking, Throwable, Unit]

  }

  def get[K, V, R](key: K)(implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]) =
    ZIO.accessM[ZEnv with ResPoolCache[K, V, R] with MyLogging](svc => svc.get[ResPoolCache.Service[K, V, R]].get(key))

  def info[K, V, R](implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]) =
    ZIO.accessM[ZEnv with ResPoolCache[K, V, R] with MyLogging](svc => svc.get[ResPoolCache.Service[K, V, R]].info)

  def make[K, V, R](timeToLiveMs: Int, limit: Int, updatef: (R, K) => ZIO[ZEnv with MyLogging, Throwable, V])(
    implicit ord: K => Ordered[K],
    tagged: Tag[R],
    tagged1: Tag[K],
    tagged2: Tag[V]
  ) /*: ZLayer[Has[ResPool.Service[R]], Nothing, Has[ResPoolCache.Service[K, V, R]]]*/ =
    (for {
      queue <- ZQueue.bounded[K](1)
      service <- ZIO.access[Has[ResPool.Service[R]]](
                  rp => makeService[K, V, R](rp.get, timeToLiveMs, limit, updatef, queue)
                )

      _ <- queue.take.flatMap(key => service.doFreeSpace) /*.repeatN(4)*/ .forever.forkDaemon

    } yield (service)).toLayer

  //old make - without queue based request for cleaning
  def make2[K, V, R](
    timeToLiveMs: Int,
    limit: Int,
    updatef: (R, K) => ZIO[ZEnv with MyLogging, Throwable, V]
  )(
    implicit ord: K => Ordered[K],
    tagged: Tag[R],
    tagged1: Tag[K],
    tagged2: Tag[V]
  ): ZLayer[Has[ResPool.Service[R]], Nothing, Has[ResPoolCache.Service[K, V, R]]] =
    ZLayer.fromService[ResPool.Service[R], ResPoolCache.Service[K, V, R]](
      rp => makeService[K, V, R](rp, timeToLiveMs, limit, updatef, null)
    )

  def makeService[K, V, R](
    rp: ResPool.Service[R],
    timeToLiveMs: Int,
    limit: Int,
    updatef: (R, K) => ZIO[ZEnv with MyLogging, Throwable, V],
    q: zio.Queue[K]
  )(
    implicit ord: K => Ordered[K],
    tagged: Tag[R],
    tagged1: Tag[K],
    tagged2: Tag[V]
  ): ResPoolCache.Service[K, V, R] = {
    new Service[K, V, R] {

      val lru_tbl   = new LRUListWithCounter[K]
      val cache_tbl = new SkipList[ValuePair[K, CacheEntry[V]]]
      cache_tbl.FACTOR = COMMON_FACTOR

      val s_tbl = new SkipList[ValuePair[Int, Semaphore]]
      s_tbl.FACTOR = 4

      //metrics
      val total   = new AtomicLong(0)
      val refresh = new AtomicLong(0)
      val hits    = new AtomicLong(0)
      val adds    = new AtomicLong(0)
      val evcts   = new AtomicLong(0)

      def info = ZIO {
        val sb = new StringBuilder()
        sb.append("*Cache table*\n")
        cache_tbl.print(sb)
        sb.append("\n*LRU table*\n")
        lru_tbl.stat(sb)

        sb.append(lru_tbl.count.toString + "\n")

        sb.append("\n*Semaphore table*\n")
        s_tbl.print(sb)

        val t = total.get()
        val h = hits.get()
        val r = refresh.get()

        sb.append("\n*Metrics*")
        sb.append("\nLimit:        " + limit)
        sb.append("\nTTL:          " + timeToLiveMs + " ms\n")
        sb.append("\nCache factor: " + cache_tbl.FACTOR)
        sb.append("\nLRU factor:   " + lru_tbl.factor)

        sb.append("\nTotal:       " + t)
        sb.append("\nHits:        " + h)
        sb.append("\nRefresh:     " + r)
        sb.append("\nAdds:        " + adds.get())
        sb.append("\nEvicts:      " + evcts.get())

        val usage  = if (t != 0) 100 * h / t else 0
        val tusage = if (t != 0) 100 * (h + r) / t else 0

        sb.append("\n\nUsage:       " + usage + "%\n")
        sb.append("Total usage: " + tusage + "%\n")

        sb.append("\nFree memory: " + Runtime.getRuntime().freeMemory() / 1000000 + " mb")
        sb.append("\nMax memory: " + Runtime.getRuntime().maxMemory / 1000000 + " mb")

        sb.toString()

      }

      def get(key: K): ZIO[zio.ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V] =
        for {
          (_, sem) <- acquireSemaphore(key) //compare and set based singleton for the given key - only one for the key
          result   <- sem.withPermit(get_(key))
          _        <- cleanLRU(key)
        } yield (result)

      /////////////////////////////////////////////////////////////////////////////////////
      def get_(key: K): ZIO[zio.ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V] =
        ZManaged
          .make(rp.acquire)(c => rp.release(c).catchAll(_ => ZIO.unit))
          .use(resource => {
            for {
              _     <- ZIO.succeed(total.incrementAndGet())
              entry <- cache_tbl.u_get(ValuePair[K, CacheEntry[V]](key))
              r <- entry match {
                    //refresh or read value from cache
                    //--------------------------------------------------
                    case Some(pair) =>
                      val cached_entry = pair.value
                      if (cached_entry.isExpired(timeToLiveMs)) {
                        refresh.incrementAndGet()
                        val old_ts = cached_entry.ts
                        for {
                          _ <- MyLogging.log(
                                "console",
                                LogLevel.Trace,
                                "ResPoolCache: key = " + key.toString() + " expired with " + cached_entry.ts
                              )
                          _ <- updatef(resource, key)
                                .flatMap(v => {
                                  if (lru_tbl.remove(new LRUQEntry[K](old_ts, key)) == true) {
                                    cached_entry.cached_val = v
                                    cached_entry.timeStampIt
                                    lru_tbl.add(new LRUQEntry[K](cached_entry.ts, key))
                                  }
                                  ZIO.unit
                                }) *> MyLogging.log(
                                "console",
                                LogLevel.Trace,
                                "ResPoolCache: key = " + key.toString() + " value refreshed"
                              )

                        } yield (cached_entry.cached_val)
                      } else
                        ZIO.succeed { hits.incrementAndGet(); entry.get.value.cached_val }
                    // read and cache the value
                    //--------------------------------------------------
                    case None =>
                      //nothing cached for the key
                      for {
                        _ <- MyLogging.log(
                              "console",
                              LogLevel.Trace,
                              "ResPoolCache: key = " + key.toString() + " attempt to cache a new value"
                            )
                        v <- updatef(resource, key)
                              .flatMap(v => {
                                for {
                                  entry <- ZIO.effect(new CacheEntry(v))
                                  _     <- ZIO.effect(entry.timeStampIt)

                                  res <- cache_tbl.u_add(ValuePair(key, entry))
                                  _   <- ZIO.effect(lru_tbl.add(new LRUQEntry[K](entry.ts, key))).when(res == true)
                                  _   <- ZIO.succeed(adds.incrementAndGet()).when(res == true)

                                  _ <- MyLogging.log(
                                        "console",
                                        LogLevel.Trace,
                                        "ResPoolCache: key = " + key
                                          .toString() + " promise acquired, new value cached"
                                      )
                                } yield (v)
                              })

                      } yield (v)
                  }
            } yield (r)
          })

      private def cleanLRU(key: K) =
        for {
          cntr <- ZIO(lru_tbl.count)
          //_ <- MyLogging.log(
          //      "console",
          //      LogLevel.Trace,
          //      "ResPoolCache: Remove LRU entry to free space for the key = " + key.toString()
          //    )
          _ <- q.offer(key).when(lru_tbl.count > limit)
        } yield ()

      private def cleanLRU3_par(e: LRUQEntry[K]) = {
     
        val T = for {
          b <- ZIO(lru_tbl.remove(e))
          _ <- ZIO { cache_tbl.remove(ValuePair(e.key)) }.when(b == true)
          _ <- ZIO(evcts.incrementAndGet())

        } yield ()
        acquireSemaphore(e.key).flatMap(p1 => { p1._2.withPermit(T) })
      }

      def doFreeSpace = {
        val temp  = lru_tbl.count - limit
        val temp2 = if (temp > 0) temp else 0
        val T = for {
          a <- ZIO(lru_tbl.lru_tbl.head)
          f1 <- cleanLRU3_par(a).fork
          _  <- f1.await
        } yield ()

        zio.blocking.blocking(T)
      }

      ///////////////////////////////////////////////////////////////////////////////////////
      private def acquireSemaphore(key: K) = {
        //println( key.hashCode.toString + "  " + code )
        val T = for {
          code <- ZIO(key.hashCode % 1024)
          //_ <- ZIO( println ( code ))
          val1 <- s_tbl.u_get(ValuePair[Int, Semaphore](code, null))
          result <- val1 match {
                     case Some(rec) => UIO.succeed((false, rec.value))
                     case None =>
                       Semaphore
                         .make(permits = 1)
                         .flatMap(sem => {
                           s_tbl
                             .u_add(ValuePair(code, sem))
                             .flatMap(added => {
                               if (added == true) UIO(true, sem)
                               else
                                 UIO(false, null) //already added, repeat it to read what was added by other fiber
                             })

                         })
                   }

        } yield (result)

        T.repeatWhile(_._2 == null)
      }

    } //end
  }

}