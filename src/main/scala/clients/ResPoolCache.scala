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

import zio.Promise

import zhttp.clients.util.SkipList
import zhttp.clients.util.ValuePair

import zhttp.MyLogging.MyLogging
import zhttp.MyLogging
import zhttp.LogLevel

import scala.volatile
import java.util.concurrent.atomic.AtomicInteger

object ResPoolCache {

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
    private val lru_tbl   = new SkipList[LRUQEntry[K]]
    private val lru_count = new AtomicInteger(0)
    lru_tbl.FACTOR = 14

    def add(e: LRUQEntry[K]) = {
      val b = lru_tbl.add(e)
      if (b) lru_count.getAndIncrement
      b
    }

    def remove(e: LRUQEntry[K]) = {
      val b = lru_tbl.remove(e)
      if (b) lru_count.getAndDecrement()
      b
    }

    def count = lru_count.get

    def head = lru_tbl.head

    def stat(sb: StringBuilder) = lru_tbl.print(sb)
  }

  type ResPoolCache[K, V, R] = Has[ResPoolCache.Service[K, V, R]]

  trait Service[K, V, R] {
    def get(key: K): ZIO[zio.ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V]

    def info: ZIO[ZEnv, Throwable, String]

  }

  def get[K, V, R](key: K)(implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]) =
    ZIO.accessM[ZEnv with ResPoolCache[K, V, R] with MyLogging](svc => svc.get[ResPoolCache.Service[K, V, R]].get(key))

  def info[K, V, R](implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]) =
    ZIO.accessM[ZEnv with ResPoolCache[K, V, R] with MyLogging](svc => svc.get[ResPoolCache.Service[K, V, R]].info)

  def make[K, V, R](
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
      rp =>
        new Service[K, V, R] {

          val cache_tbl = new SkipList[ValuePair[K, CacheEntry[V]]]
          cache_tbl.FACTOR = 14
          val p_tbl = new SkipList[ValuePair[K, Promise[Throwable, Boolean]]]
          p_tbl.FACTOR = 50

          val lru_tbl = new LRUListWithCounter[K]

          def info = ZIO {
            val sb = new StringBuilder()
            sb.append("*Cache table*\n")
            //cache_tbl.debug_print_layers(sb)
            cache_tbl.print( sb )
            sb.append("\n*LRU table*\n")
            lru_tbl.stat(sb).toString()
          }

          /////////////////////////////////////////////////////////////////////////////////////
          def get(key: K): ZIO[zio.ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V] =
            ZManaged
              .make(rp.acquire)(c => rp.release(c).catchAll(_ => ZIO.unit))
              .use(resource => {
                for {
                  //_  <- ZIO ( println( key ) )
                  entry <- cache_tbl.u_get(ValuePair[K, CacheEntry[V]](key))
                  r <- entry match {
                        //refresh or read value from cache
                        //--------------------------------------------------
                        case Some(pair) =>
                          val cached_entry = pair.value
                          if (cached_entry.isExpired(timeToLiveMs)) {
                            val old_ts = cached_entry.ts
                            for {
                              _ <- MyLogging.log(
                                    "console",
                                    LogLevel.Trace,
                                    "ResPoolCache: key = " + key.toString() + " expired with " + cached_entry.ts
                                  )
                              //shared barier by key, only one fiber can pass and be a promise owner
                              pttt    <- acquirePromise(key)
                              aquired = pttt._1
                              promise = pttt._2
                              _ <- if (aquired == true) {

                                    updatef(resource, key)
                                      .flatMap(v => {
                                        if (lru_tbl.remove(new LRUQEntry[K](old_ts, key)) == true) {
                                          //very important point, we don't remove/add cache on refresh, we just update fields.
                                          //this introduce conflicts we are to solve here - but it's a performace gain.
                                          cached_entry.cached_val = v
                                          cached_entry.timeStampIt
                                          lru_tbl.add(new LRUQEntry[K](cached_entry.ts, key))
                                        }
                                        promise.succeed(true) *> dropPromise(key);
                                      }) *> MyLogging.log(
                                      "console",
                                      LogLevel.Trace,
                                      "ResPoolCache: key = " + key.toString() + " promise acquired, value refreshed"
                                    )

                                  } else {
                                    promise.await *>
                                      MyLogging.log(
                                        "console",
                                        LogLevel.Trace,
                                        "ResPoolCache: key = " + key
                                          .toString() + " wait on promise succeeded, value received"
                                      )
                                  }
                            } yield (cached_entry.cached_val)
                          } else
                            ZIO.succeed(entry.get.value.cached_val)
                        // read and cache the value
                        //--------------------------------------------------
                        case None =>
                          //nothing cached for the key
                          for { //obtain shared promise for the key, promise owner or client
                            _ <- MyLogging.log(
                                  "console",
                                  LogLevel.Trace,
                                  "ResPoolCache: key = " + key.toString() + " attempt to cache a new value"
                                )
                            //shared barier by key, only one fiber can pass and be a promise owner
                            pttt    <- acquirePromise(key)
                            aquired = pttt._1
                            promise = pttt._2
                            v <- if (aquired == true) {
                                  updatef(resource, key)
                                    .flatMap(v => {
                                      for {
                                        entry <- ZIO.effect(new CacheEntry(v))
                                        _     <- ZIO.effect(entry.timeStampIt)
                                        res   <- cache_tbl.u_add(ValuePair(key, entry))
                                        _     <- ZIO.effect(lru_tbl.add(new LRUQEntry[K](entry.ts, key)))
                                        _ <- ZIO.effect(cleanLRU(key, entry)).flatMap { b =>
                                              if (b)
                                                MyLogging.log(
                                                  "console",
                                                  LogLevel.Trace,
                                                  "ResPoolCache: key = " + key
                                                    .toString() + " space freed, least recently element removed"
                                                )
                                              else ZIO.unit
                                            }

                                        _ <- promise.succeed(res);
                                        _ <- dropPromise(key)
                                        _ <- MyLogging.log(
                                              "console",
                                              LogLevel.Trace,
                                              "ResPoolCache: key = " + key
                                                .toString() + " promise acquired, new value cached"
                                            )
                                      } yield (v)
                                    })
                                } else {
                                  for {
                                    _   <- promise.await
                                    opt <- cache_tbl.u_get(ValuePair(key))
                                    _ <- MyLogging.log(
                                          "console",
                                          LogLevel.Trace,
                                          "ResPoolCache: key = " + key
                                            .toString() + " wait on promise for new value succeeded"
                                        )
                                  } yield (opt.get.value.cached_val) //if exception - something wrong with the code

                                }
                          } yield (v)

                      }
                } yield (r)
              })

          //////////////////////////////////////////////////////////////////
          //what happens here is that many parallel requests compete for one
          //lru_head, but we don't really worry if cache size will grow +- n items
          //eventualy it will be consistent.... maybe ZQueue many to one ( offer/take is better ).
          //will see, so far no issues with tests.
          private def cleanLRU(key: K, entry: CacheEntry[V]): Boolean = {
            var res: Boolean = false
           //cache the count or we never finish
            var cntr = lru_tbl.count
            while ( cntr > limit) {
              res = true
              val lru_head = lru_tbl.head
              cache_tbl.remove(ValuePair(lru_head.key))
              lru_tbl.remove(lru_head)
              cntr = cntr - 1
              //println(lru_tbl.count + "  ->  " + cache_tbl.count)
            }
            res
          }

          ///////////////////////////////////////////////////////////////////////////////////////
          def dropPromise(key: K) = p_tbl.u_remove(ValuePair(key))

          ///////////////////////////////////////////////////////////////////////////////////////
          def acquirePromise(key: K): UIO[(Boolean, Promise[Throwable, Boolean])] = {
            val T = for {
              val1 <- p_tbl.u_get(ValuePair[K, Promise[Throwable, Boolean]](key, null))
              result <- val1 match {
                         case Some(rec) => UIO.succeed((false, rec.value))
                         case None =>
                           Promise
                             .make[Throwable, Boolean]
                             .flatMap(promise => {
                               p_tbl
                                 .u_add(ValuePair(key, promise))
                                 .flatMap(added => {
                                   if (added == true) UIO(true, promise)
                                   else
                                     UIO(false, null) //already added, repeat it to read what was added by other fiber
                                 })

                             })
                       }

            } yield (result)

            T.repeatWhile(_._2 == null)
          }

        }
    )

}
