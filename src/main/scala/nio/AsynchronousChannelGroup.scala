package nio.channels

import java.io.IOException
import java.nio.channels.{ AsynchronousChannelGroup => JAsynchronousChannelGroup }
import java.nio.channels.spi.{ AsynchronousChannelProvider => JAsynchronousChannelProvider }

import java.util.concurrent.{ ExecutorService => JExecutorService, ThreadFactory => JThreadFactory }
import java.util.concurrent.TimeUnit

import zio.{ IO, UIO, Managed }
import zio.duration.Duration

object AsynchronousChannelGroup {

  def apply(executor: JExecutorService, initialSize: Int): IO[Exception, AsynchronousChannelGroup] =
    IO.effect(
        new AsynchronousChannelGroup(
          JAsynchronousChannelGroup.withCachedThreadPool(executor, initialSize)
        )
      )
      .refineToOrDie[Exception]

  def apply(
    threadsNo: Int,
    threadsFactory: JThreadFactory
  ): IO[Exception, AsynchronousChannelGroup] =
    IO.effect(
        new AsynchronousChannelGroup(
          JAsynchronousChannelGroup.withFixedThreadPool(threadsNo, threadsFactory)
        )
      )
      .refineToOrDie[Exception]

  def apply(executor: JExecutorService): IO[Exception, AsynchronousChannelGroup] =
    IO.effect(
        new AsynchronousChannelGroup(JAsynchronousChannelGroup.withThreadPool(executor))
      )
      .refineToOrDie[Exception]
   
   //used in Zlayer construction, before unsafeRun ZIO cycle   
   def make( executor: JExecutorService ) = new AsynchronousChannelGroup(JAsynchronousChannelGroup.withThreadPool(executor))   
}

class AsynchronousChannelGroup(private[channels] val channelGroup: JAsynchronousChannelGroup) {

  def awaitTermination(timeout: Duration): IO[Exception, Boolean] =
    IO.effect(channelGroup.awaitTermination(timeout.toMillis, TimeUnit.MILLISECONDS))
      .refineToOrDie[Exception]


  def openAsynchronousServerSocketChannel(): Managed[Exception, AsynchronousServerSocketChannel] = 
  {
    val open = IO.effectTotal {
                new AsynchronousServerSocketChannel( 
                    channelGroup.provider().openAsynchronousServerSocketChannel( channelGroup ) )
    }
    Managed.make(open)(_.close.orDie)
  }    

  val isShutdown: UIO[Boolean] = IO.effectTotal(channelGroup.isShutdown)

  val isTerminated: UIO[Boolean] = IO.effectTotal(channelGroup.isTerminated)

  val provider: UIO[JAsynchronousChannelProvider] = IO.effectTotal(channelGroup.provider())

  val shutdown: UIO[Unit] = IO.effectTotal(channelGroup.shutdown())

  val shutdownNow: IO[IOException, Unit] =
    IO.effect(channelGroup.shutdownNow()).refineToOrDie[IOException]
}
