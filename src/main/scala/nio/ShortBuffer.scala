package nio

import zio.{ Chunk, IO, ZIO }
import java.nio.{ BufferUnderflowException, ByteOrder, ReadOnlyBufferException, ShortBuffer => JShortBuffer }

final class ShortBuffer(val shortBuffer: JShortBuffer) extends Buffer[Short](shortBuffer) {

  override protected[nio] def array: IO[Exception, Array[Short]] =
    IO.attempt(shortBuffer.array()).refineToOrDie[Exception]

  override def order: ByteOrder = shortBuffer.order()

  override def slice: IO[Nothing, ShortBuffer] =
    IO.succeed(shortBuffer.slice()).map(new ShortBuffer(_))

  override def compact: IO[ReadOnlyBufferException, Unit] =
    IO.attempt(shortBuffer.compact()).unit.refineToOrDie[ReadOnlyBufferException]

  override def duplicate: IO[Nothing, ShortBuffer] =
    IO.succeed(new ShortBuffer(shortBuffer.duplicate()))

  def withJavaBuffer[R, E, A](f: JShortBuffer => ZIO[R, E, A]): ZIO[R, E, A] = f(shortBuffer)

  override def get: IO[BufferUnderflowException, Short] =
    IO.attempt(shortBuffer.get()).refineToOrDie[BufferUnderflowException]

  override def get(i: Int): IO[IndexOutOfBoundsException, Short] =
    IO.attempt(shortBuffer.get(i)).refineToOrDie[IndexOutOfBoundsException]

  override def getChunk(maxLength: Int): IO[BufferUnderflowException, Chunk[Short]] =
    IO.attempt {
        val array = Array.ofDim[Short](math.min(maxLength, shortBuffer.remaining()))
        shortBuffer.get(array)
        Chunk.fromArray(array)
      }
      .refineToOrDie[BufferUnderflowException]

  override def put(element: Short): IO[Exception, Unit] =
    IO.attempt(shortBuffer.put(element)).unit.refineToOrDie[Exception]

  override def put(index: Int, element: Short): IO[Exception, Unit] =
    IO.attempt(shortBuffer.put(index, element)).unit.refineToOrDie[Exception]

  override def putChunk(chunk: Chunk[Short]): IO[Exception, Unit] =
    IO.attempt {
        val array = chunk.toArray
        shortBuffer.put(array)
      }
      .unit
      .refineToOrDie[Exception]

  override def asReadOnlyBuffer: IO[Nothing, ShortBuffer] =
    IO.succeed(shortBuffer.asReadOnlyBuffer()).map(new ShortBuffer(_))

}
