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

  type ResPoolCache[K, V, R] = Has[ResPoolCache.Service[K, V, R]]

  trait Service[K, V, R] {
    def get(key: K): ZIO[zio.ZEnv with ResPoolCache[K, V, R] with MyLogging, Throwable, V]
  }

  def get[K, V, R](key: K)(implicit tagged: Tag[R], tagged1: Tag[K], tagged2: Tag[V]) =
    ZIO.accessM[ZEnv with ResPoolCache[K, V, R] with MyLogging](svc => svc.get[ResPoolCache.Service[K, V, R]].get(key))

  def make[K, V, R](
    timeToLiveMs: Int,
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
          cache_tbl.FACTOR = 12
          val p_tbl = new SkipList[ValuePair[K, Promise[Throwable, Boolean]]]
          p_tbl.FACTOR = 50

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
                            for {
                              _ <- MyLogging.log(
                                    "console",
                                    LogLevel.Trace,
                                    "ResPoolCache: key = " + key.toString() + " expired with " + cached_entry.ts
                                  )
                              pttt    <- acquirePromise(key)
                              aquired = pttt._1
                              promise = pttt._2
                              _ <- if (aquired == true) {

                                    updatef(resource, key)
                                      .flatMap(v => {
                                        cached_entry.cached_val = v
                                        cached_entry.timeStampIt
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
                                        _     <- promise.succeed(res);
                                        _     <- dropPromise(key)
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
