package nio

import zio.{ Chunk, IO, ZIO }
import java.nio.{ BufferUnderflowException, ByteOrder, ReadOnlyBufferException, DoubleBuffer => JDoubleBuffer }

final class DoubleBuffer(doubleBuffer: JDoubleBuffer) extends Buffer[Double](doubleBuffer) {

  override protected[nio] def array: IO[Exception, Array[Double]] =
    ZIO.attempt(doubleBuffer.array()).refineToOrDie[Exception]

  override def order: ByteOrder = doubleBuffer.order

  override def slice: IO[Nothing, DoubleBuffer] =
    ZIO.succeed(doubleBuffer.slice()).map(new DoubleBuffer(_))

  override def compact: IO[ReadOnlyBufferException, Unit] =
    ZIO.attempt(doubleBuffer.compact()).unit.refineToOrDie[ReadOnlyBufferException]

  override def duplicate: IO[Nothing, DoubleBuffer] =
    ZIO.succeed(new DoubleBuffer(doubleBuffer.duplicate()))

  def withJavaBuffer[R, E, A](f: JDoubleBuffer => ZIO[R, E, A]): ZIO[R, E, A] = f(doubleBuffer)

  override def get: IO[BufferUnderflowException, Double] =
    ZIO.attempt(doubleBuffer.get()).refineToOrDie[BufferUnderflowException]

  override def get(i: Int): IO[IndexOutOfBoundsException, Double] =
    ZIO.attempt(doubleBuffer.get(i)).refineToOrDie[IndexOutOfBoundsException]

  override def getChunk(
    maxLength: Int = Int.MaxValue
  ): IO[BufferUnderflowException, Chunk[Double]] =
    ZIO.attempt {
        val array = Array.ofDim[Double](math.min(maxLength, doubleBuffer.remaining()))
        doubleBuffer.get(array)
        Chunk.fromArray(array)
      }
      .refineToOrDie[BufferUnderflowException]

  override def put(element: Double): IO[Exception, Unit] =
    ZIO.attempt(doubleBuffer.put(element)).unit.refineToOrDie[Exception]

  override def put(index: Int, element: Double): IO[Exception, Unit] =
    ZIO.attempt(doubleBuffer.put(index, element)).unit.refineToOrDie[Exception]

  override def putChunk(chunk: Chunk[Double]): IO[Exception, Unit] =
    ZIO.attempt {
        val array = chunk.toArray
        doubleBuffer.put(array)
      }
      .unit
      .refineToOrDie[Exception]

  override def asReadOnlyBuffer: IO[Nothing, DoubleBuffer] =
    ZIO.succeed(doubleBuffer.asReadOnlyBuffer()).map(new DoubleBuffer(_))

}
