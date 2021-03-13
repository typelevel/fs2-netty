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

import cats.Eval
import cats.effect.std.Dispatcher
import cats.effect.{Async, Sync}
import cats.syntax.all._
import fs2.netty.pipeline.BytePipeline._
import fs2.netty.{NettyChannelInitializer, NettyPipeline, Socket}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{Channel, ChannelInitializer, ChannelPipeline}
import io.netty.handler.codec.bytes.ByteArrayDecoder

class BytePipeline[F[_]: Async](
  byteArrayPipeline: NettyPipeline[F, ByteBuf, Array[Byte]]
) extends NettyChannelInitializer[F, Chunk[Byte], Byte] {

  override def toChannelInitializer[C <: Channel](
    cb: Socket[F, Chunk[Byte], Byte] => F[Unit]
  ): F[ChannelInitializer[C]] =
    byteArrayPipeline
      .toChannelInitializer { byteArraySocket =>
        /*
        TODO: Can't do this b/c ProFunctor isn't Chunk aware
          Sync[F].delay(byteArraySocket.dimap[Chunk[Byte], Byte](toByteBuf)(Chunk.array(_)))
         */
        Sync[F].delay(new ChunkingByteSocket[F](byteArraySocket)).flatMap(cb)
      }

}

object BytePipeline {

  def apply[F[_]: Async](dispatcher: Dispatcher[F]): F[BytePipeline[F]] =
    for {
      pipeline <- NettyPipeline[F, ByteBuf, Array[Byte]](
        dispatcher,
        handlers = List(
          Eval.always(new ByteArrayDecoder)
        )
      )
    } yield new BytePipeline(pipeline)

  implicit val byteArraySocketDecoder: Socket.Decoder[Array[Byte]] = {
    case array: Array[Byte] => array.asRight[String]
    case _ =>
      "pipeline is misconfigured".asLeft[Array[Byte]]
  }

  private class ChunkingByteSocket[F[_]: Async](
    socket: Socket[F, ByteBuf, Array[Byte]]
  ) extends Socket[F, Chunk[Byte], Byte] {

    override lazy val reads: Stream[F, Byte] =
      socket.reads.map(Chunk.array(_)).flatMap(Stream.chunk)

    override lazy val events: Stream[F, AnyRef] = socket.events

    override def write(output: Chunk[Byte]): F[Unit] =
      socket.write(toByteBuf(output))

    override lazy val writes: Pipe[F, Chunk[Byte], INothing] =
      _.map(toByteBuf).through(socket.writes)

    override val isOpen: F[Boolean] = socket.isOpen

    override val isClosed: F[Boolean] = socket.isClosed

    override val isDetached: F[Boolean] = socket.isDetached

    override def close(): F[Unit] = socket.close()

    override def mutatePipeline[O2, I2: Socket.Decoder](
      mutator: ChannelPipeline => F[Unit]
    ): F[Socket[F, O2, I2]] = socket.mutatePipeline[O2, I2](mutator)

  }

  // TODO: alloc over unpooled?
  private def toByteBuf(chunk: Chunk[Byte]): ByteBuf =
    chunk match {
      case Chunk.ArraySlice(arr, off, len) =>
        Unpooled.wrappedBuffer(arr, off, len)

      case c: Chunk.ByteBuffer =>
        Unpooled.wrappedBuffer(c.toByteBuffer)

      case c =>
        Unpooled.wrappedBuffer(c.toArray)
    }

}
