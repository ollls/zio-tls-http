package zhttp

import java.nio.ByteBuffer
import java.nio.BufferUnderflowException
import zio.ZIO
import zio.stream.ZTransducer
import zio.Chunk

import zio.ZRef
import zio.Ref


object FrameTransducer {
  def make() = ZTransducer[Any, Nothing, Byte, WebSocketFrame] {
    def makeFrame(
      tx: Ref[FrameTranscoder],
      leftOverRef: Ref[Chunk[Byte]],
      in: Chunk[Byte] = Chunk.empty
    ): ZIO[Any, Throwable, Chunk[WebSocketFrame]] =
      for {
        frames   <- tx.get
        leftOver <- leftOverRef.get
        data <- ZIO.succeed(leftOver ++ in)
        bb   <- ZIO.succeed(ByteBuffer.wrap(data.toArray))

        byteBuf <- ZIO.attempt(Option(frames.bufferToFrame(bb)))

        b <- byteBuf match {
              case None =>
                leftOverRef.set(Chunk.fromByteBuffer(bb)) *>
                  ZIO.succeed(Chunk.empty)
              case Some(value) =>
                leftOverRef.set(Chunk.fromByteBuffer(bb.slice())) *>
                  ZIO.succeed(Chunk.single(value))
            }
      } yield (b)

    for {
      buf <- ZRef.makeManaged[Chunk[Byte]](Chunk.empty)
      func <- ZRef
               .makeManaged(new FrameTranscoder(isClient = false))
               .map(
                 frames =>
                   (_: Option[Chunk[Byte]]) match {
                     case None =>
                       makeFrame(frames, buf).tap(_ => buf.set(Chunk.empty)).orDie
                     case Some(in) =>
                       makeFrame(frames, buf, in).catchSome {
                         case _: BufferUnderflowException =>
                           buf.update(old => old ++ in) *>
                             ZIO.succeed(Chunk.empty)
                       }.orDie
                   }
               )
    } yield (func)
  }
}
