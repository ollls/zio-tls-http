package zhttp

import nio.SocketAddress
import nio.channels.AsynchronousTlsByteChannel
import nio.channels.AsynchronousSocketChannel
import zio.{ZIO,ZEnv,Chunk}


trait Channel {

    def read: ZIO[ZEnv, Exception, Chunk[Byte]]

    def readBuffer( bb : java.nio.ByteBuffer ) : ZIO[ZEnv, Exception, Unit ]

    def write(chunk: Chunk[Byte]): ZIO[ZEnv, Exception, Int]

    def remoteAddress : ZIO[ ZEnv, Exception, Option[SocketAddress] ]

    def keepAlive( ms : Long ) : Unit


}


class TlsChannel( c : AsynchronousTlsByteChannel) extends Channel
{
    def read: ZIO[ZEnv, Exception, Chunk[Byte]] = c.read

    def readBuffer( bb : java.nio.ByteBuffer ) : ZIO[ZEnv, Exception, Unit ] = c.readBuffer( bb )

    def write(chunk: Chunk[Byte]): ZIO[ZEnv, Exception, Int] = c.write( chunk )

    def remoteAddress = c.remoteAddress

    def keepAlive( ms : Long ) : Unit = c.keepAlive(ms).asInstanceOf[Unit]


}



class TcpChannel( c : AsynchronousSocketChannel) extends Channel
{
    final val HTTP_READ_PACKET = 16384

    def read: ZIO[ZEnv, Exception, Chunk[Byte]] = c.read2( HTTP_READ_PACKET )

    def readBuffer( bb : java.nio.ByteBuffer ) : ZIO[ZEnv, Exception, Unit] = {
        zio.IO.unit
    }

    def write(chunk: Chunk[Byte]): ZIO[ZEnv, Exception, Int] = c.write( chunk )

    def remoteAddress = c.remoteAddress

    def keepAlive( ms : Long ) = c.keepAlive( ms ).asInstanceOf[Unit]

    def close =  c.close

}