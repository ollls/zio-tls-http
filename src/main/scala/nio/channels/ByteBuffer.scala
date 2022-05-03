package nio

import zio.{ Chunk, IO, UIO, ZIO }
import java.nio.{ BufferUnderflowException, ByteOrder, ReadOnlyBufferException, ByteBuffer => JByteBuffer }

class ByteBuffer protected[nio] (protected[nio] val byteBuffer: JByteBuffer) extends Buffer[Byte](byteBuffer) {

  final override protected[nio] def array: IO[UnsupportedOperationException, Array[Byte]] =
    ZIO.attempt(byteBuffer.array()).refineToOrDie[UnsupportedOperationException]

  final def order: ByteOrder = byteBuffer.order()

  def order(o: ByteOrder): UIO[Unit] =
    ZIO.succeed(byteBuffer.order(o)).unit

  final override def slice: IO[Nothing, ByteBuffer] =
    ZIO.succeed(byteBuffer.slice()).map(new ByteBuffer(_))

  final override def compact: IO[ReadOnlyBufferException, Unit] =
    ZIO.attempt(byteBuffer.compact()).unit.refineToOrDie[ReadOnlyBufferException]

  final override def duplicate: IO[Nothing, ByteBuffer] =
    ZIO.succeed(new ByteBuffer(byteBuffer.duplicate()))

  def withJavaBuffer[R, E, A](f: JByteBuffer => ZIO[R, E, A]): ZIO[R, E, A] = f(byteBuffer)

  final override def get: IO[BufferUnderflowException, Byte] =
    ZIO.attempt(byteBuffer.get()).refineToOrDie[BufferUnderflowException]

  final override def get(i: Int): IO[IndexOutOfBoundsException, Byte] =
    ZIO.attempt(byteBuffer.get(i)).refineToOrDie[IndexOutOfBoundsException]

  final override def getChunk(maxLength: Int = Int.MaxValue): IO[BufferUnderflowException, Chunk[Byte]] =
    ZIO.attempt {
        val array = Array.ofDim[Byte](math.min(maxLength, byteBuffer.remaining()))
        byteBuffer.get(array)
        Chunk.fromArray(array)
      }
      .refineToOrDie[BufferUnderflowException]

  final override def put(element: Byte): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.put(element)).unit.refineToOrDie[Exception]

  final override def put(index: Int, element: Byte): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.put(index, element)).unit.refineToOrDie[Exception]

  def putByteBuffer(source: ByteBuffer): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.put(source.byteBuffer)).unit.refineToOrDie[Exception]

  final override def putChunk(chunk: Chunk[Byte]): IO[Exception, Unit] =
    ZIO.attempt {
        val array = chunk.toArray
        byteBuffer.put(array)
      }
      .unit
      .refineToOrDie[Exception]

  final override def asReadOnlyBuffer: IO[Nothing, ByteBuffer] =
    ZIO.succeed(byteBuffer.asReadOnlyBuffer()).map(new ByteBuffer(_))

  final def asCharBuffer: IO[Nothing, CharBuffer] =
    ZIO.succeed(new CharBuffer(byteBuffer.asCharBuffer()))

  final def asDoubleBuffer: IO[Nothing, DoubleBuffer] =
    ZIO.succeed(new DoubleBuffer(byteBuffer.asDoubleBuffer()))

  final def asFloatBuffer: IO[Nothing, FloatBuffer] =
    ZIO.succeed(new FloatBuffer(byteBuffer.asFloatBuffer()))

  final def asIntBuffer: IO[Nothing, IntBuffer] =
    ZIO.succeed(new IntBuffer(byteBuffer.asIntBuffer()))

  final def asLongBuffer: IO[Nothing, LongBuffer] =
    ZIO.succeed(new LongBuffer(byteBuffer.asLongBuffer()))

  final def asShortBuffer: IO[Nothing, ShortBuffer] =
    ZIO.succeed(new ShortBuffer(byteBuffer.asShortBuffer()))

  final def putChar(value: Char): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putChar(value)).unit.refineToOrDie[Exception]

  final def putChar(index: Int, value: Char): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putChar(index, value)).unit.refineToOrDie[Exception]

  final def putDouble(value: Double): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putDouble(value)).unit.refineToOrDie[Exception]

  final def putDouble(index: Int, value: Double): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putDouble(index, value)).unit.refineToOrDie[Exception]

  final def putFloat(value: Float): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putFloat(value)).unit.refineToOrDie[Exception]

  final def putFloat(index: Int, value: Float): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putFloat(index, value)).unit.refineToOrDie[Exception]

  final def putInt(value: Int): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putInt(value)).unit.refineToOrDie[Exception]

  final def putInt(index: Int, value: Int): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putInt(index, value)).unit.refineToOrDie[Exception]

  final def putLong(value: Long): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putLong(value)).unit.refineToOrDie[Exception]

  final def putLong(index: Int, value: Long): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putLong(index, value)).unit.refineToOrDie[Exception]

  final def putShort(value: Short): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putShort(value)).unit.refineToOrDie[Exception]

  final def putShort(index: Int, value: Short): IO[Exception, Unit] =
    ZIO.attempt(byteBuffer.putShort(index, value)).unit.refineToOrDie[Exception]

  final def getChar: IO[BufferUnderflowException, Char] =
    ZIO.attempt(byteBuffer.getChar()).refineToOrDie[BufferUnderflowException]

  final def getChar(index: Int): IO[IndexOutOfBoundsException, Char] =
    ZIO.attempt(byteBuffer.getChar(index)).refineToOrDie[IndexOutOfBoundsException]

  final def getDouble: IO[BufferUnderflowException, Double] =
    ZIO.attempt(byteBuffer.getDouble()).refineToOrDie[BufferUnderflowException]

  final def getDouble(index: Int): IO[IndexOutOfBoundsException, Double] =
    ZIO.attempt(byteBuffer.getDouble(index)).refineToOrDie[IndexOutOfBoundsException]

  final def getFloat: IO[BufferUnderflowException, Float] =
    ZIO.attempt(byteBuffer.getFloat()).refineToOrDie[BufferUnderflowException]

  final def getFloat(index: Int): IO[IndexOutOfBoundsException, Float] =
    ZIO.attempt(byteBuffer.getFloat(index)).refineToOrDie[IndexOutOfBoundsException]

  final def getInt: IO[BufferUnderflowException, Int] =
    ZIO.attempt(byteBuffer.getInt()).refineToOrDie[BufferUnderflowException]

  final def getInt(index: Int): IO[IndexOutOfBoundsException, Int] =
    ZIO.attempt(byteBuffer.getInt(index)).refineToOrDie[IndexOutOfBoundsException]

  final def getLong: IO[BufferUnderflowException, Long] =
    ZIO.attempt(byteBuffer.getLong()).refineToOrDie[BufferUnderflowException]

  final def getLong(index: Int): IO[IndexOutOfBoundsException, Long] =
    ZIO.attempt(byteBuffer.getLong(index)).refineToOrDie[IndexOutOfBoundsException]

  final def getShort: IO[BufferUnderflowException, Short] =
    ZIO.attempt(byteBuffer.getShort()).refineToOrDie[BufferUnderflowException]

  final def getShort(index: Int): IO[IndexOutOfBoundsException, Short] =
    ZIO.attempt(byteBuffer.getShort(index)).refineToOrDie[IndexOutOfBoundsException]
}
