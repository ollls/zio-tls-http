package nio.channels

import java.io.IOException
import java.nio.channels.{ AsynchronousChannelGroup => JAsynchronousChannelGroup }
import java.nio.channels.spi.{ AsynchronousChannelProvider => JAsynchronousChannelProvider }

import java.util.concurrent.{ ExecutorService => JExecutorService, ThreadFactory => JThreadFactory }
import java.util.concurrent.TimeUnit

import zio.{ IO, UIO }
import zio.Duration
import zio.ZIO
import zio.Scope

object AsynchronousChannelGroup {

  def apply(executor: JExecutorService, initialSize: Int): IO[Exception, AsynchronousChannelGroup] =
    IO.attempt(
        new AsynchronousChannelGroup(
          JAsynchronousChannelGroup.withCachedThreadPool(executor, initialSize)
        )
      )
      .refineToOrDie[Exception]

  def apply(
    threadsNo: Int,
    threadsFactory: JThreadFactory
  ): IO[Exception, AsynchronousChannelGroup] =
    IO.attempt(
        new AsynchronousChannelGroup(
          JAsynchronousChannelGroup.withFixedThreadPool(threadsNo, threadsFactory)
        )
      )
      .refineToOrDie[Exception]

  def apply(executor: JExecutorService): IO[Exception, AsynchronousChannelGroup] =
    IO.attempt(
        new AsynchronousChannelGroup(JAsynchronousChannelGroup.withThreadPool(executor))
      )
      .refineToOrDie[Exception]
   
   //used in Zlayer construction, before unsafeRun ZIO cycle   
   def make( executor: JExecutorService ) = new AsynchronousChannelGroup(JAsynchronousChannelGroup.withThreadPool(executor))   
}

class AsynchronousChannelGroup(private[channels] val channelGroup: JAsynchronousChannelGroup) {

  def awaitTermination(timeout: Duration): IO[Exception, Boolean] =
    IO.attempt(channelGroup.awaitTermination(timeout.toMillis, TimeUnit.MILLISECONDS))
      .refineToOrDie[Exception]


  def openAsynchronousServerSocketChannel() : ZIO[Scope, Nothing, AsynchronousServerSocketChannel] = 
  {
    val open = IO.succeed {
                new AsynchronousServerSocketChannel( 
                    channelGroup.provider().openAsynchronousServerSocketChannel( channelGroup ) )
    }
    ZIO.acquireRelease(open)(_.close.orDie)
  }    

  def openAsynchronousServerSocketChannelWith() = 
  {
    val open = IO.succeed {
                new AsynchronousServerSocketChannel( 
                    channelGroup.provider().openAsynchronousServerSocketChannel( channelGroup ) )
    }
    ZIO.acquireReleaseWith(open)(_.close.orDie)
  } 

  val isShutdown: UIO[Boolean] = IO.succeed(channelGroup.isShutdown)

  val isTerminated: UIO[Boolean] = IO.succeed(channelGroup.isTerminated)

  val provider: UIO[JAsynchronousChannelProvider] = IO.succeed(channelGroup.provider())

  val shutdown: UIO[Unit] = IO.succeed(channelGroup.shutdown())

  val shutdownNow: IO[IOException, Unit] =
    IO.attempt(channelGroup.shutdownNow()).refineToOrDie[IOException]
}
