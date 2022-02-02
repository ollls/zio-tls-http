package nio

import zio.{ Chunk, IO, ZIO }
import java.nio.{ BufferUnderflowException, ByteOrder, ReadOnlyBufferException, LongBuffer => JLongBuffer }

final class LongBuffer(val longBuffer: JLongBuffer) extends Buffer[Long](longBuffer) {

  override protected[nio] def array: IO[Exception, Array[Long]] =
    IO.attempt(longBuffer.array()).refineToOrDie[Exception]

  override def order: ByteOrder = longBuffer.order

  override def slice: IO[Nothing, LongBuffer] =
    IO.succeed(longBuffer.slice()).map(new LongBuffer(_))

  override def compact: IO[ReadOnlyBufferException, Unit] =
    IO.attempt(longBuffer.compact()).unit.refineToOrDie[ReadOnlyBufferException]

  override def duplicate: IO[Nothing, LongBuffer] =
    IO.succeed(new LongBuffer(longBuffer.duplicate()))

  def withJavaBuffer[R, E, A](f: JLongBuffer => ZIO[R, E, A]): ZIO[R, E, A] = f(longBuffer)

  override def get: IO[BufferUnderflowException, Long] =
    IO.attempt(longBuffer.get()).refineToOrDie[BufferUnderflowException]

  override def get(i: Int): IO[IndexOutOfBoundsException, Long] =
    IO.attempt(longBuffer.get(i)).refineToOrDie[IndexOutOfBoundsException]

  override def getChunk(maxLength: Int = Int.MaxValue): IO[BufferUnderflowException, Chunk[Long]] =
    IO.attempt {
        val array = Array.ofDim[Long](math.min(maxLength, longBuffer.remaining()))
        longBuffer.get(array)
        Chunk.fromArray(array)
      }
      .refineToOrDie[BufferUnderflowException]

  override def put(element: Long): IO[Exception, Unit] =
    IO.attempt(longBuffer.put(element)).unit.refineToOrDie[Exception]

  override def put(index: Int, element: Long): IO[Exception, Unit] =
    IO.attempt(longBuffer.put(index, element)).unit.refineToOrDie[Exception]

  override def putChunk(chunk: Chunk[Long]): IO[Exception, Unit] =
    IO.attempt {
        val array = chunk.toArray
        longBuffer.put(array)
      }
      .unit
      .refineToOrDie[Exception]

  override def asReadOnlyBuffer: IO[Nothing, LongBuffer] =
    IO.succeed(longBuffer.asReadOnlyBuffer()).map(new LongBuffer(_))

}
