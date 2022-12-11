package zhttp.netio

import zio.{ZIO, Task}

import javax.net.ssl.{SSLEngine => JSSLEngine}

import javax.net.ssl.SSLEngineResult

import java.nio.ByteBuffer
import java.lang.Runnable

final class SSLEngine(val engine: JSSLEngine) {

  def wrap(src: ByteBuffer, dst: ByteBuffer): Task[SSLEngineResult] =
    ZIO.attempt(engine.wrap(src, dst))

  def unwrap(src: ByteBuffer, dst: ByteBuffer): Task[SSLEngineResult] = {
    ZIO.attempt(engine.unwrap(src, dst))
  }

  def closeInbound() = ZIO.attempt(engine.closeInbound())

  def closeOutbound() = ZIO.attempt(engine.closeOutbound())

  def isOutboundDone() = ZIO.attempt(engine.isOutboundDone)

  def isInboundDone() = ZIO.attempt(engine.isInboundDone)

  def setUseClientMode(use: Boolean) = ZIO.attempt(engine.setUseClientMode(use))

  def setNeedClientAuth(use: Boolean) =
    ZIO.attempt(engine.setNeedClientAuth(use))

  def getDelegatedTask() = ZIO.attemptBlocking {
    var task: Runnable = null
    while {
      task = engine.getDelegatedTask();
      task != null
    }
    do task.run()
  }

  def getHandshakeStatus(): Task[SSLEngineResult.HandshakeStatus] =
    ZIO.attempt(engine.getHandshakeStatus())

}
