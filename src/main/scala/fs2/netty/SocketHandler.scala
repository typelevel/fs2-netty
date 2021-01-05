/*
 * Copyright 2020 Daniel Spiewak
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
package netty

import cats.effect.{Async, Sync}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.channel.socket.SocketChannel

private final class SocketHandler[F[_]: Async](
    disp: Dispatcher[F],
    channel: SocketChannel,
    chunks: Queue[F, ByteBuf])
    extends ChannelInboundHandlerAdapter
    with Socket[F] {

  val read: F[Chunk[Byte]] =
    Sync[F].delay(channel.read()) *> chunks.take.map(toChunk)

  def write(bytes: Chunk[Byte]): F[Unit] =
    fromNettyFuture[F](Sync[F].delay(channel.writeAndFlush(toByteBuf(bytes)))).void

  val isOpen: F[Boolean] =
    Sync[F].delay(channel.isOpen())

  val close: F[Unit] =
    fromNettyFuture[F](Sync[F].delay(channel.close())).void

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) =
    disp.unsafeRunAndForget(chunks.offer(msg.asInstanceOf[ByteBuf]))

  private[this] def toByteBuf(chunk: Chunk[Byte]): ByteBuf =
    chunk match {
      case Chunk.ArraySlice(arr, off, len) =>
        Unpooled.wrappedBuffer(arr, off, len)

      case c: Chunk.ByteBuffer =>
        Unpooled.wrappedBuffer(c.toByteBuffer)

      case c =>
        Unpooled.wrappedBuffer(c.toArray)
    }

  private[this] def toChunk(buf: ByteBuf): Chunk[Byte] =
    if (buf.hasArray())
      Chunk.array(buf.array())
    else if (buf.nioBufferCount() > 0)
      Chunk.byteBuffer(buf.nioBuffer())
    else
      ???
}
