package zhttp.netio

import java.nio.ByteBuffer
import zio.Task
import zio.Chunk
import java.net.SocketAddress

trait IOChannel {
  private var timeOut_ms : Int = 4000

  def read(timeOut: Int): Task[Chunk[Byte]]
  def read(): Task[Chunk[Byte]] = read( timeOut_ms )

  def write(buffer: ByteBuffer): Task[Int]
  def close(): Task[Unit]
  def remoteAddress() : Task[SocketAddress]

  def timeOutMs( ts : Int) : Unit = timeOut_ms = ts
  def timeOutMs : Int = timeOut_ms
}
