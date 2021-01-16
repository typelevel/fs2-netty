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
package netty

import cats.{Applicative, ApplicativeError, Functor}
import cats.effect.{Async, Poll, Sync}
import cats.syntax.all._

import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.channel.socket.SocketChannel

import scala.annotation.tailrec

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

private final class SocketHandler[F[_]: Async](channel: SocketChannel)
    extends ChannelInboundHandlerAdapter
    with Socket[F] {

  private[this] var buf: ByteBuf = _
  private[this] var error: Throwable = _
  private[this] val continue = new AtomicReference[Either[Throwable, Unit] => Unit]

  private[this] val RightU = Right(())
  private[this] val Signal: Either[Throwable, Unit] => Unit = _ => ()   // don't ask...

  val localAddress: F[InetSocketAddress] =
    Sync[F].delay(channel.localAddress())

  val remoteAddress: F[InetSocketAddress] =
    Sync[F].delay(channel.remoteAddress())

  // if this returns null it means termination
  private[this] def take(poll: Poll[F]): F[ByteBuf] =
    Sync[F] defer {
      if (buf == null && error == null) {
        val gate = poll {
          Async[F].async_[Unit] { k =>
            if (!continue.compareAndSet(null, k)) {
              continue.set(null)    // it's the Signal now
              k(RightU)
            }
          }
        }

        val read: F[ByteBuf] = Sync[F] defer {
          if (error != null) {
            if (buf != null) {
              buf.release()
              buf = null
            }

            val t = error
            error = null
            ApplicativeError[F, Throwable].raiseError(t)
          } else {
            val b = buf
            buf = null
            b.pure[F]
          }
        }

        gate *> read
      } else if (error != null) {
        if (buf != null) {
          buf.release()
          buf = null
        }

        val t = error
        error = null
        ApplicativeError[F, Throwable].raiseError(t)
      } else {
        val b = buf
        buf = null
        b.pure[F]
      }
    }

  private[this] val fetch: Stream[F, ByteBuf] =
    Stream.bracketFull[F, ByteBuf](poll => Sync[F].delay(channel.read()) *> take(poll)) { (b, _) =>
      if (b != null)
        Sync[F].delay(b.release()).void
      else
        Applicative[F].unit
    }

  lazy val reads: Stream[F, Byte] =
    Stream force {
      Functor[F].ifF(isOpen)(
        fetch.flatMap(b => if (b == null) Stream.empty else Stream.chunk(toChunk(b))) ++ reads,
        Stream.empty)
    }

  def write(bytes: Chunk[Byte]): F[Unit] =
    fromNettyFuture[F](Sync[F].delay(channel.writeAndFlush(toByteBuf(bytes)))).void

  val writes: Pipe[F, Byte, INothing] =
    _.chunks.evalMap(c => write(c) *> isOpen).takeWhile(b => b).drain

  val isOpen: F[Boolean] =
    Sync[F].delay(channel.isOpen())

  val close: F[Unit] =
    fromNettyFuture[F](Sync[F].delay(channel.close())).void

  @tailrec
  private[this] def fire(): Unit = {
    if (continue.get() != null)
      continue.getAndSet(null)(RightU)
    else if (!continue.compareAndSet(null, Signal))
      fire()
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) = {
    buf = msg.asInstanceOf[ByteBuf]
    fire()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable) = {
    error = t
    fire()
  }

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
