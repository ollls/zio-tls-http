package nio

import zio.IO



import javax.net.ssl.{ SSLEngine => JSSLEngine }

import javax.net.ssl.SSLEngineResult

import java.nio.{ ByteBuffer => JByteBuffer }
import java.lang.Runnable
import zio.ZIO.attemptBlocking

final class SSLEngine(val engine: JSSLEngine) {

  def wrap(src: Buffer[Byte], dst: Buffer[Byte]): IO[Exception, SSLEngineResult] =
    IO.attempt(engine.wrap(src.buffer.asInstanceOf[JByteBuffer], dst.buffer.asInstanceOf[JByteBuffer]))
      .refineToOrDie[Exception]

  def unwrap(src: Buffer[Byte], dst: Buffer[Byte]): IO[Exception, SSLEngineResult] =
    IO.attempt(engine.unwrap(src.buffer.asInstanceOf[JByteBuffer], dst.buffer.asInstanceOf[JByteBuffer]))
      .refineToOrDie[Exception]

  def closeInbound() = IO.attempt(engine.closeInbound()).refineToOrDie[Exception]

  def closeOutbound() = IO.attempt(engine.closeOutbound()).refineToOrDie[Exception]

  def isOutboundDone() = IO.attempt(engine.isOutboundDone).refineToOrDie[Exception]

  def isInboundDone() = IO.attempt(engine.isInboundDone).refineToOrDie[Exception]

  def setUseClientMode(use: Boolean) = IO.attempt(engine.setUseClientMode(use)).refineToOrDie[Exception]

  def setNeedClientAuth(use: Boolean) = IO.attempt(engine.setNeedClientAuth(use)).refineToOrDie[Exception]

  def getDelegatedTask() = attemptBlocking {
    var task : Runnable = null
    while
      task  = engine.getDelegatedTask()
      task != null
    do task.run()    
  }

  def getHandshakeStatus(): IO[Exception, SSLEngineResult.HandshakeStatus] =
    IO.attempt(engine.getHandshakeStatus()).refineToOrDie[Exception]

}
