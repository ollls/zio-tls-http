/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package zhttp

import java.nio.charset.StandardCharsets.UTF_8
import scala.util.hashing.MurmurHash3

import zio.Chunk

//import scodec.bits.ByteVector

abstract class WebSocketFrame {

 /*
      *  %x0 denotes a continuation frame

      *  %x1 denotes a text frame

      *  %x2 denotes a binary frame

      *  %x3-7 are reserved for further non-control frames

      *  %x8 denotes a connection close

      *  %x9 denotes a ping

      *  %xA denotes a pong

      *  %xB-F are reserved for further control frames
*/

  def opcode: Int
  def data: Chunk[Byte]
  def last: Boolean

  final def length: Int = data.length.toInt

  override def equals(obj: Any): Boolean =
    obj match {
      case wf: WebSocketFrame =>
        this.opcode == wf.opcode &&
          this.last == wf.last &&
          this.data == wf.data
      case _ => false
    }

  override def hashCode: Int = {
    var hash = WebSocketFrame.hashSeed
    hash = MurmurHash3.mix(hash, opcode.##)
    hash = MurmurHash3.mix(hash, data.##)
    hash = MurmurHash3.mixLast(hash, last.##)
    hash
  }
}

object WebSocketFrame {

  val  CONTINUATION = 0x0
  val  TEXT         = 0x1
  val  BINARY       = 0x3
  val  CLOSE        = 0x8
  val  PING         = 0x9 
  val  PONG         = 0xA

  private val hashSeed = MurmurHash3.stringHash("WebSocketFrame")

  sealed abstract class ControlFrame extends WebSocketFrame {
    final def last: Boolean = true
  }


  /*
  private class BinaryText(val data: Chunk[Byte], val last: Boolean) extends Text {
    lazy val str: String = new String(data.toArray, UTF_8)
  }*/

  final case class Text( val str: String, val last: Boolean) extends  WebSocketFrame {
    def opcode = TEXT
    lazy val data: Chunk[Byte] = Chunk.fromArray(str.getBytes(UTF_8))
  }

  //object Text {
  //  def apply(str: String, last: Boolean = true): Text = new Text(str, last)
  //  def unapply(txt: Text): Option[(String, Boolean)] = Some((txt.str, txt.last))
 // }

  //object Binary {
  //    def apply(data: Chunk[Byte], last: Boolean): Binary = new Binary(data, last)
 // }

  final case class Binary(data: Chunk[Byte], last: Boolean = true) extends WebSocketFrame {
    def opcode = BINARY
    override def toString: String = s"Binary(Array(${data.length}), last: $last)"
  }

  final case class Continuation(data: Chunk[Byte], last: Boolean) extends WebSocketFrame {
    def opcode: Int = CONTINUATION
    override def toString: String = s"Continuation(Array(${data.length}), last: $last)"
  }

  final case class Ping(data: Chunk[Byte] = Chunk.empty ) extends ControlFrame {
    def opcode = PING
    override def toString: String =
      if (data.length > 0) s"Ping(Array(${data.length}))"
      else s"Ping"
  }

  final case class Pong(data: Chunk[Byte] = Chunk.empty ) extends ControlFrame {
    def opcode = PONG
    override def toString: String =
      if (data.length > 0) s"Pong(Array(${data.length}))"
      else s"Pong"
  }

  final case class Close( data : Chunk[Byte] = Chunk.empty ) extends ControlFrame {
    def opcode = CLOSE

    def closeCode: Int =
      if (data.length > 0)
        (data(0) << 8 & 0xff00) | (data(1) & 0xff) // 16-bit unsigned
      else 1005 // No code present

    override def toString: String =
      if (data.length > 0) s"Close(Array(${data.length}))"
      else s"Close"
  }

  sealed abstract class InvalidCloseDataException extends RuntimeException
  class InvalidCloseCodeException(val i: Int) extends InvalidCloseDataException
  class ReasonTooLongException(val s: String) extends InvalidCloseDataException

  private def toUnsignedShort(x: Int) = Array[Byte](((x >> 8) & 0xff).toByte, (x & 0xff).toByte)

  private def reasonToBytes(reason: String) : Either[InvalidCloseDataException, Chunk[Byte]] = {
    val asBytes : Chunk[Byte]= Chunk.fromArray(reason.getBytes(UTF_8))
    if (asBytes.length > 123)
      Left(new ReasonTooLongException(reason))
    else
      Right(asBytes)
  }

  private def closeCodeToBytes(code: Int): Either[InvalidCloseCodeException, Chunk[Byte]] =
    if (code < 1000 || code > 4999) Left(new InvalidCloseCodeException(code))
    else Right( Chunk.fromArray(toUnsignedShort(code)))

  object Close {
    def apply(code: Int): Either[InvalidCloseDataException, Close] =
      closeCodeToBytes(code).map(Close(_))

    def apply(code: Int, reason: String): Either[InvalidCloseDataException, Close] =
      for {
        c <- closeCodeToBytes(code): Either[InvalidCloseDataException, Chunk[Byte]]
        r <- reasonToBytes(reason)
      } yield Close( c ++ r )
  }
}
