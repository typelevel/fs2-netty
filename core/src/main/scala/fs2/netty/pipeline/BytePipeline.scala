package fs2
package netty.pipeline

import cats.effect.std.Dispatcher
import cats.effect.{Async, Sync}
import cats.syntax.all._
import fs2.netty.pipeline.BytePipeline._
import fs2.netty.{NettyChannelInitializer, NettyPipeline, Socket}
import fs2.{Chunk, INothing, Pipe}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{Channel, ChannelInitializer, ChannelPipeline}
import io.netty.handler.codec.bytes.ByteArrayDecoder

class BytePipeline[F[_]: Async](dispatcher: Dispatcher[F])
    extends NettyChannelInitializer[F, Byte, Chunk[Byte], Nothing] {

  private val byteArrayPipeline =
    new NettyPipeline[F, Array[Byte], ByteBuf, Nothing](
      handlers = List(
        new ByteArrayDecoder
      ) // TODO: eval, not sharable. Test with 2 channels using same NettyPipeline class.
    )(dispatcher)

  override def toChannelInitializer[C <: Channel](
    cb: Socket[F, Byte, Chunk[Byte], Nothing] => F[Unit]
  ): F[ChannelInitializer[C]] =
    byteArrayPipeline
      .toChannelInitializer { byteArraySocket =>
        Sync[F].delay(new ChunkingByteSocket[F](byteArraySocket)).flatMap(cb)
      }
}

object BytePipeline {

  def apply[F[_]: Async](dispatcher: Dispatcher[F]): F[BytePipeline[F]] =
    Sync[F].delay(new BytePipeline(dispatcher))

  implicit val byteArraySocketDecoder: Socket.Decoder[Array[Byte]] = {
    case array: Array[Byte] => array.asRight[String]
    case _ =>
      "pipeline is misconfigured".asLeft[Array[Byte]]
  }

  private class ChunkingByteSocket[F[_]: Async](
    socket: Socket[F, Array[Byte], ByteBuf, Nothing]
  ) extends Socket[F, Byte, Chunk[Byte], Nothing] {

    override lazy val reads: fs2.Stream[F, Byte] =
      socket.reads.map(Chunk.array(_)).flatMap(Stream.chunk)

    override lazy val events: fs2.Stream[F, Nothing] = socket.events

    override def write(output: Chunk[Byte]): F[Unit] =
      socket.write(toByteBuf(output))

    override lazy val writes: Pipe[F, Chunk[Byte], INothing] =
      _.map(toByteBuf).through(socket.writes)

    override val isOpen: F[Boolean] = socket.isOpen

    override val isClosed: F[Boolean] = socket.isClosed

    override val isDetached: F[Boolean] = socket.isDetached

    override def close(): F[Unit] = socket.close()

    override def mutatePipeline[I2: Socket.Decoder, O2, E2](
      mutator: ChannelPipeline => F[Unit]
    ): F[Socket[F, I2, O2, E2]] = socket.mutatePipeline[I2, O2, E2](mutator)

    // TODO: alloc over unpooled?
    private[this] def toByteBuf(chunk: Chunk[Byte]): ByteBuf =
      chunk match {
        case Chunk.ArraySlice(arr, off, len) =>
          Unpooled.wrappedBuffer(arr, off, len)

        case c: Chunk.ByteBuffer =>
          Unpooled.wrappedBuffer(c.toByteBuffer)

        case c =>
          Unpooled.wrappedBuffer(c.toArray)
      }
  }
}
