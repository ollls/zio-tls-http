package zhttp

import java.nio.ByteBuffer
import java.nio.BufferUnderflowException
import zio.ZIO
import zio.Chunk
import zio.Ref

import zio.stream.ZPipeline
import zio.stream.ZChannel

object FramePipeline {

  def make : ZPipeline[Any, Exception, Byte, WebSocketFrame ] = 
  {
    def make2( frames : FrameTranscoder, leftOver : Chunk[Byte]) : ZChannel[Any, Nothing, Chunk[Byte], Any, Exception, Chunk[WebSocketFrame], Any] = 
    {  
       val z = ZChannel.readWith( ( in : Chunk[Byte] ) => {

        val data = leftOver ++ in
        val bb   = ByteBuffer.wrap(data.toArray)
        val opt_buf = Option( frames.bufferToFrame( bb ) )

        opt_buf match{ 
          case None => make2( frames, Chunk.fromByteBuffer( bb ) ) 
          case Some(value) => ZChannel.write( Chunk.single( value )) *> make2( frames, Chunk.fromByteBuffer( bb.slice() ) )
        }


       },

       (err: Exception) => ZChannel.fail(err),
       (done: Any) => ZChannel.succeed( true ) )

       z
    }
    /*+:class ZPipeline[-Env, +Err, -In, +Out](val channel: ZChannel[Env, Nothing, Chunk[In], Any, Err, Chunk[Out], Any])*/

    val t = make2( new FrameTranscoder(isClient = false),  Chunk.empty );

    ZPipeline.fromChannel( t )
  
  }  


}
/*
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
*/
