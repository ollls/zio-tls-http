package nio

import zio.{ Chunk, IO, ZIO }
import java.nio.{ BufferUnderflowException, ByteOrder, ReadOnlyBufferException, CharBuffer => JCharBuffer }

final class CharBuffer(charBuffer: JCharBuffer) extends Buffer[Char](charBuffer) {

  override protected[nio] def array: IO[Exception, Array[Char]] =
    IO.attempt(charBuffer.array()).refineToOrDie[Exception]

  override def order: ByteOrder = charBuffer.order()

  override def slice: IO[Nothing, CharBuffer] =
    IO.succeed(charBuffer.slice()).map(new CharBuffer(_))

  override def compact: IO[ReadOnlyBufferException, Unit] =
    IO.attempt(charBuffer.compact()).unit.refineToOrDie[ReadOnlyBufferException]

  override def duplicate: IO[Nothing, CharBuffer] =
    IO.succeed(new CharBuffer(charBuffer.duplicate()))

  def withJavaBuffer[R, E, A](f: JCharBuffer => ZIO[R, E, A]): ZIO[R, E, A] = f(charBuffer)

  override def get: IO[BufferUnderflowException, Char] =
    IO.attempt(charBuffer.get()).refineToOrDie[BufferUnderflowException]

  override def get(i: Int): IO[IndexOutOfBoundsException, Char] =
    IO.attempt(charBuffer.get(i)).refineToOrDie[IndexOutOfBoundsException]

  override def getChunk(maxLength: Int = Int.MaxValue): IO[BufferUnderflowException, Chunk[Char]] =
    IO.attempt {
        val array = Array.ofDim[Char](math.min(maxLength, charBuffer.remaining()))
        charBuffer.get(array)
        Chunk.fromArray(array)
      }
      .refineToOrDie[BufferUnderflowException]

  def getString: IO[Nothing, String] = IO.succeed(charBuffer.toString())

  override def put(element: Char): IO[Exception, Unit] =
    IO.attempt(charBuffer.put(element)).unit.refineToOrDie[Exception]

  override def put(index: Int, element: Char): IO[Exception, Unit] =
    IO.attempt(charBuffer.put(index, element)).unit.refineToOrDie[Exception]

  override def putChunk(chunk: Chunk[Char]): IO[Exception, Unit] =
    IO.attempt {
        val array = chunk.toArray
        charBuffer.put(array)
      }
      .unit
      .refineToOrDie[Exception]

  override def asReadOnlyBuffer: IO[Nothing, CharBuffer] =
    IO.succeed(charBuffer.asReadOnlyBuffer()).map(new CharBuffer(_))

}
