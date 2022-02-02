package nio

import java.nio.{
  BufferUnderflowException,
  ByteOrder,
  ReadOnlyBufferException,
  Buffer => JBuffer,
  ByteBuffer => JByteBuffer,
  CharBuffer => JCharBuffer,
  DoubleBuffer => JDoubleBuffer,
  FloatBuffer => JFloatBuffer,
  IntBuffer => JIntBuffer,
  LongBuffer => JLongBuffer,
  ShortBuffer => JShortBuffer
}

import zio.{ Chunk, IO, UIO, ZIO }

import scala.reflect.ClassTag

@specialized // See if Specialized will work on return values, e.g. `get`
abstract class Buffer[A: ClassTag] private[nio] (private[nio] val buffer: JBuffer) {

  final def capacity: Int = buffer.capacity

  def order: ByteOrder

  final def position: UIO[Int] = IO.succeed(buffer.position)

  final def position(newPosition: Int): IO[Exception, Unit] =
    IO.attempt(buffer.position(newPosition)).unit.refineToOrDie[Exception]

  final def limit: UIO[Int] = IO.succeed(buffer.limit)

  final def remaining: UIO[Int] = IO.succeed(buffer.remaining)

  final def hasRemaining: UIO[Boolean] = IO.succeed(buffer.hasRemaining)

  final def limit(newLimit: Int): IO[Exception, Unit] =
    IO.attempt(buffer.limit(newLimit)).unit.refineToOrDie[Exception]

  final def mark: UIO[Unit] = IO.succeed(buffer.mark()).unit

  final def reset: IO[Exception, Unit] =
    IO.attempt(buffer.reset()).unit.refineToOrDie[Exception]

  final def clear: UIO[Unit] = IO.succeed(buffer.clear()).unit

  final def flip: UIO[Unit] = IO.succeed(buffer.flip()).unit

  final def rewind: UIO[Unit] = IO.succeed(buffer.rewind()).unit

  final def isReadOnly: Boolean = buffer.isReadOnly

  final def hasArray: Boolean = buffer.hasArray

  final def isDirect: Boolean = buffer.isDirect

  def slice: IO[Nothing, Buffer[A]]

  def compact: IO[ReadOnlyBufferException, Unit]

  def duplicate: IO[Nothing, Buffer[A]]

  final def withArray[R, E, B](
    noArray: ZIO[R, E, B]
  )(
    hasArray: (Array[A], Int) => ZIO[R, E, B]
  ): ZIO[R, E, B] =
    if (buffer.hasArray) {
      for {
        a      <- array.orDie
        offset <- IO.attempt(buffer.arrayOffset()).orDie
        result <- hasArray(a, offset)
      } yield {
        result
      }
    } else {
      noArray
    }

  protected[nio] def array: IO[Exception, Array[A]]

  def get: IO[BufferUnderflowException, A]

  def get(i: Int): IO[IndexOutOfBoundsException, A]

  def getChunk(maxLength: Int = Int.MaxValue): IO[BufferUnderflowException, Chunk[A]]

  def put(element: A): IO[Exception, Unit]

  def put(index: Int, element: A): IO[Exception, Unit]

  def putChunk(chunk: Chunk[A]): IO[Exception, Unit]

  def asReadOnlyBuffer: IO[Nothing, Buffer[A]]

}

object Buffer {

  def byte( jb : JByteBuffer ) = new ByteBuffer( jb )

  def byte(capacity: Int): IO[IllegalArgumentException, ByteBuffer] =
    IO.attempt(JByteBuffer.allocate(capacity))
      .map(new ByteBuffer(_))
      .refineToOrDie[IllegalArgumentException]

  def byte(chunk: Chunk[Byte]): IO[Nothing, ByteBuffer] =
    IO.succeed(JByteBuffer.wrap(chunk.toArray)).map(new ByteBuffer(_))

  def byteDirect(capacity: Int): IO[IllegalArgumentException, ByteBuffer] =
    IO.attempt(new ByteBuffer(JByteBuffer.allocateDirect(capacity)))
      .refineToOrDie[IllegalArgumentException]

  def char(capacity: Int): IO[IllegalArgumentException, CharBuffer] =
    IO.attempt(JCharBuffer.allocate(capacity))
      .map(new CharBuffer(_))
      .refineToOrDie[IllegalArgumentException]

  def char(chunk: Chunk[Char]): IO[Nothing, CharBuffer] =
    IO.succeed(JCharBuffer.wrap(chunk.toArray)).map(new CharBuffer(_))

  def char(
    charSequence: CharSequence,
    start: Int,
    end: Int
  ): IO[IndexOutOfBoundsException, CharBuffer] =
    IO.attempt(new CharBuffer(JCharBuffer.wrap(charSequence, start, end)))
      .refineToOrDie[IndexOutOfBoundsException]

  def char(charSequence: CharSequence): IO[Nothing, CharBuffer] =
    IO.succeed(new CharBuffer(JCharBuffer.wrap(charSequence)))

  def float(capacity: Int): IO[IllegalArgumentException, FloatBuffer] =
    IO.attempt(JFloatBuffer.allocate(capacity))
      .map(new FloatBuffer(_))
      .refineToOrDie[IllegalArgumentException]

  def float(chunk: Chunk[Float]): IO[Nothing, FloatBuffer] =
    IO.succeed(JFloatBuffer.wrap(chunk.toArray)).map(new FloatBuffer(_))

  def double(capacity: Int): IO[IllegalArgumentException, DoubleBuffer] =
    IO.attempt(JDoubleBuffer.allocate(capacity))
      .map(new DoubleBuffer(_))
      .refineToOrDie[IllegalArgumentException]

  def double(chunk: Chunk[Double]): IO[Nothing, DoubleBuffer] =
    IO.succeed(JDoubleBuffer.wrap(chunk.toArray)).map(new DoubleBuffer(_))

  def int(capacity: Int): IO[IllegalArgumentException, IntBuffer] =
    IO.attempt(JIntBuffer.allocate(capacity))
      .map(new IntBuffer(_))
      .refineToOrDie[IllegalArgumentException]

  def int(chunk: Chunk[Int]): IO[Nothing, IntBuffer] =
    IO.succeed(JIntBuffer.wrap(chunk.toArray)).map(new IntBuffer(_))

  def long(capacity: Int): IO[IllegalArgumentException, LongBuffer] =
    IO.attempt(JLongBuffer.allocate(capacity))
      .map(new LongBuffer(_))
      .refineToOrDie[IllegalArgumentException]

  def long(chunk: Chunk[Long]): IO[Nothing, LongBuffer] =
    IO.succeed(JLongBuffer.wrap(chunk.toArray)).map(new LongBuffer(_))

  def short(capacity: Int): IO[IllegalArgumentException, ShortBuffer] =
    IO.attempt(JShortBuffer.allocate(capacity))
      .map(new ShortBuffer(_))
      .refineToOrDie[IllegalArgumentException]

  def short(chunk: Chunk[Short]): IO[Nothing, ShortBuffer] =
    IO.succeed(JShortBuffer.wrap(chunk.toArray)).map(new ShortBuffer(_))

}
