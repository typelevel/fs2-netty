/*
 * Copyright 2021 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fs2
package netty.pipeline

import cats.effect.std.Dispatcher
import cats.effect.{Async, Sync}
import cats.syntax.all._
import fs2.netty.pipeline.AlternativeBytePipeline.ByteBufToByteChunkSocket
import fs2.netty.{NettyChannelInitializer, NettyPipeline, Socket}
import fs2.{Chunk, INothing, Pipe, Stream}
import io.netty.buffer.{ByteBuf, ByteBufUtil, Unpooled}
import io.netty.channel.{Channel, ChannelInitializer, ChannelPipeline}

// This class and BytePipeline highlight the different way to create
// sockets, i.e. rely on Netty handlers or encode transforms in fs2.
class AlternativeBytePipeline[F[_]: Async](
  byteBufPipeline: NettyPipeline[F, ByteBuf, ByteBuf]
) extends NettyChannelInitializer[F, Byte, Chunk[Byte]] {

  override def toChannelInitializer[C <: Channel](
    cb: Socket[F, Byte, Chunk[Byte]] => F[Unit]
  ): F[ChannelInitializer[C]] =
    byteBufPipeline
      .toChannelInitializer { byteBufSocket =>
        Sync[F]
          .delay(new ByteBufToByteChunkSocket[F](byteBufSocket))
          .flatMap(cb)
      }
}

object AlternativeBytePipeline {

  def apply[F[_]: Async](
    dispatcher: Dispatcher[F]
  ): F[AlternativeBytePipeline[F]] =
    for {
      byteBufPipeline <- NettyPipeline.apply[F](dispatcher)
    } yield new AlternativeBytePipeline(byteBufPipeline)

  private class ByteBufToByteChunkSocket[F[_]: Async](
    socket: Socket[F, ByteBuf, ByteBuf]
  ) extends Socket[F, Byte, Chunk[Byte]] {

    override lazy val reads: Stream[F, Byte] =
      socket.reads
        .evalMap(bb =>
          Sync[F].delay(ByteBufUtil.getBytes(bb)).map(Chunk.array(_))
        )
        .flatMap(Stream.chunk)

    override lazy val events: Stream[F, AnyRef] = socket.events

    override def write(output: Chunk[Byte]): F[Unit] =
      socket.write(toByteBuf(output))

    override lazy val writes: Pipe[F, Chunk[Byte], INothing] =
      _.map(toByteBuf).through(socket.writes)

    override val isOpen: F[Boolean] = socket.isOpen

    override val isClosed: F[Boolean] = socket.isClosed

    override val isDetached: F[Boolean] = socket.isDetached

    override def close(): F[Unit] = socket.close()

    override def mutatePipeline[I2: Socket.Decoder, O2](
      mutator: ChannelPipeline => F[Unit]
    ): F[Socket[F, I2, O2]] = socket.mutatePipeline[I2, O2](mutator)

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
