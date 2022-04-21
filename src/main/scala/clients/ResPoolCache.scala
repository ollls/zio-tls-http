package zhttp.clients

import zio.ZIO
import zio.UIO
import zio.ZLayer
import zio.ZEnv
import zhttp.MyLogging.MyLogging
import zhttp.clients.ResPool
import zio.Tag
import zio.Queue
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

  import math.Ordered.orderingToOrdered
  class LRUQEntry[K](val timestamp: Long, val key: K)( implicit ord: K => Ordered[K]) extends Ordered[LRUQEntry[K]] {

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

  type ResPoolCache[K, V, R] = ResPoolCache.Service[K, V, R]

  trait Service[K, V, R] {
    def get(key: K): ZIO[zio.ZEnv with MyLogging, Throwable, Option[V]]

    def info: ZIO[ZEnv, Throwable, String]

    def doFreeSpace: ZIO[zio.ZEnv with MyLogging, Throwable, Unit]

    def terminate: ZIO[zio.ZEnv with MyLogging, Throwable, Unit] 

  }

  def get[K, V, R](key: K)(implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]) =
    ZIO.environmentWithZIO[ZEnv with ResPoolCache[K, V, R] with MyLogging](svc => svc.get[ResPoolCache.Service[K, V, R]].get(key))

  def info[K, V, R](implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]) =
    ZIO.environmentWithZIO[ZEnv with ResPoolCache[K, V, R] with MyLogging](svc => svc.get[ResPoolCache.Service[K, V, R]].info)

  def make[K, V, R](timeToLiveMs: Int, limit: Int, updatef: (R, K) => ZIO[ZEnv with MyLogging, Throwable, Option[V]])(
    implicit ord: K => Ordered[K],
    tagged: Tag[R],
    tagged1: Tag[K],
    tagged2: Tag[V]
  ): ZLayer[ZEnv with ResPool.ResPool[R] with MyLogging.MyLogging, Nothing, ResPoolCache.ResPoolCache[K, V, R]] =
  {
    val effect = for {
      queue <- Queue.bounded[K](1)
      service <- ZIO.environmentWith[ResPool.Service[R]](
                  rp => makeService[K, V, R](rp.get, timeToLiveMs, limit, updatef, queue)
                )

      _ <- queue.take.flatMap(key => service.doFreeSpace).repeatUntilZIO( _ => queue.isShutdown ).forkDaemon
    } yield (service)
    
    ZLayer.fromZIO( effect )
  }

  private def layerName[K, V, R](implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]): String = {
    val kt = tagged.tag.shortName
    val vt = tagged1.tag.shortName
    val vr = tagged2.tag.shortName

    s"ResPoolCache[$kt,$vt,$vr]"
  }

  def makeService[K, V, R](
    rp: ResPool.Service[R],
    timeToLiveMs: Int,
    limit: Int,
    updatef: (R, K) => ZIO[ZEnv with MyLogging, Throwable, Option[V]],
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

      def info = ZIO.succeed {
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

      def terminate: ZIO[zio.ZEnv with MyLogging, Throwable, Unit] = 
          for {
          _ <- q.shutdown  
          } yield() 

      def get(key: K): ZIO[zio.ZEnv with MyLogging, Throwable, Option[V]] =
        for {
          semPair <- acquireSemaphore(key) //compare and set based singleton for the given key - only one for the key
          result   <- semPair._2.withPermit(get_(key))
          _        <- cleanLRU(key)
        } yield (result)

      /////////////////////////////////////////////////////////////////////////////////////
      def get_(key: K): ZIO[zio.ZEnv with MyLogging, Throwable, Option[V]] =
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
                            layerName[K, V, R] + ": key = " + key.toString() + " expired with " + cached_entry.ts
                          )
                      res <- ZIO.acquireReleaseWith(rp.acquire)(c => rp.release(c) )(resource => updatef(resource, key))
                              .flatMap(ov => {
                                ov match {
                                  case Some(v) =>
                                    if (lru_tbl.remove(new LRUQEntry[K](old_ts, key)) == true) {
                                      cached_entry.cached_val = v
                                      cached_entry.timeStampIt
                                      lru_tbl.add(new LRUQEntry[K](cached_entry.ts, key))
                                    }
                                    ZIO.some(v)
                                  case None => ZIO.none
                                }
                              })
                      _ <- MyLogging.log(
                            "console",
                            LogLevel.Trace,
                            layerName[K, V, R] + ": key = " + key.toString() + " value refreshed"
                          )

                    } yield (res)
                  } else
                    ZIO.succeed { hits.incrementAndGet(); Some(entry.get.value.cached_val) }
                // read and cache the value
                //--------------------------------------------------
                case None =>
                  //nothing cached for the key
                  for {
                    _ <- MyLogging.log(
                          "console",
                          LogLevel.Trace,
                          layerName[K, V, R] + ": key = " + key.toString() + " attempt to cache a new value"
                        )
                    v <- ZIO.acquireReleaseWith(rp.acquire)(c => rp.release(c))(resource => updatef(resource, key))
                          .flatMap(ov => {
                            ov match {
                              case Some(v) =>
                                for {
                                  entry <- ZIO.attempt(new CacheEntry(v))

                                  _ <- ZIO.attempt(entry.timeStampIt)

                                  res <- cache_tbl.u_add(ValuePair(key, entry))
                                  _   <- ZIO.attempt(lru_tbl.add(new LRUQEntry[K](entry.ts, key))).when(res == true)
                                  _   <- ZIO.succeed(adds.incrementAndGet()).when(res == true)

                                  _ <- MyLogging.log(
                                        "console",
                                        LogLevel.Trace,
                                        layerName[K, V, R] + ": key = " + key
                                          .toString() + " new value cached"
                                      )
                                } yield (Some(v))
                              case None => ZIO.none

                            }

                          })
                  } yield (v)
              }
        } yield (r)

      private def cleanLRU(key: K) =
        for {
          cntr <- ZIO.succeed(lru_tbl.count)
          _    <- q.offer(key).when(lru_tbl.count > limit)
        } yield ()

      private def cleanLRU3_par(e: LRUQEntry[K]) = {

        val T = for {
          _ <- MyLogging.log(
                "console",
                LogLevel.Trace,
                layerName[K, V, R] + ": Remove LRU entry = " + e.key.toString()
              )
          b <- ZIO.attempt(lru_tbl.remove(e))
          _ <- ZIO.attempt { cache_tbl.remove(ValuePair(e.key)) }.when(b == true)
          _ <- ZIO.attempt(evcts.incrementAndGet())

        } yield ()
        acquireSemaphore(e.key).flatMap(p1 => { p1._2.withPermit(T) })
      }

      def doFreeSpace = {
        val temp  = lru_tbl.count - limit
        val temp2 = if (temp > 0) temp else 0
        val T = for {
          a  <- ZIO.succeed(lru_tbl.lru_tbl.head)
          f1 <- cleanLRU3_par(a).fork
          _  <- f1.await
        } yield ()

        zio.ZIO.blocking(T)
      }

      ///////////////////////////////////////////////////////////////////////////////////////
      private def acquireSemaphore(key: K) = {
        //println( key.hashCode.toString + "  " + code )
        val T : ZIO[Any, Throwable, (Boolean, Semaphore)] = for {
          code <- ZIO.succeed(key.hashCode % 1024)
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
                               if (added == true) UIO.succeed(true, sem)
                               else
                                 UIO.succeed(false, null) //already added, repeat it to read what was added by other fiber
                             })

                         })
                   }

        } yield (result)

        T.repeatWhile( p => { p._2 == null } )

      }

    } //end
  }

}
