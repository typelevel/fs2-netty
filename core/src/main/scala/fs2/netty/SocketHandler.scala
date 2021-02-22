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

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Poll, Sync}
import cats.syntax.all._
import cats.{Applicative, Functor}
import com.comcast.ip4s.{IpAddress, SocketAddress}
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelPipeline}
import io.netty.util.ReferenceCountUtil

private final class SocketHandler[F[_]: Async, I, O, E](
  disp: Dispatcher[F],
  channel: SocketChannel,
  readsQueue: Queue[F, AnyRef], // I | Throwable | Null
  eventsQueue: Queue[F, E]
) extends ChannelInboundHandlerAdapter
    with Socket[F, I, O, E] {

  override val localAddress: F[SocketAddress[IpAddress]] =
    Sync[F].delay(SocketAddress.fromInetSocketAddress(channel.localAddress()))

  override val remoteAddress: F[SocketAddress[IpAddress]] =
    Sync[F].delay(SocketAddress.fromInetSocketAddress(channel.remoteAddress()))

  private[this] def take(poll: Poll[F]): F[Option[I]] =
    poll(readsQueue.take) flatMap {
      case null => Applicative[F].pure(none[I]) // EOF marker
      case i: I => Applicative[F].pure(i.some)
      case t: Throwable => t.raiseError[F, Option[I]]
    }

  private[this] val fetch: Stream[F, I] =
    Stream
      .bracketFull[F, Option[I]](poll =>
        Sync[F].delay(channel.read()) *> take(poll)
      ) { (i, _) =>
        if (i != null)
          Sync[F].delay(ReferenceCountUtil.safeRelease(i)).void
        else
          Applicative[F].unit
      }
      .unNoneTerminate

  override lazy val reads: Stream[F, I] =
    Stream force {
      Functor[F].ifF(isOpen)(
        fetch.flatMap(i =>
          if (i == null) Stream.empty else Stream.emit(i)
        ) ++ reads,
        Stream.empty
      )
    }

  override def write(output: O): F[Unit] =
    fromNettyFuture[F](
      Sync[F].delay(channel.writeAndFlush(output))
    ).void

  override val writes: Pipe[F, O, INothing] =
    _.evalMap(o => write(o) *> isOpen).takeWhile(bool => bool).drain

  private[this] val isOpen: F[Boolean] =
    Sync[F].delay(channel.isOpen)

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) =
    ReferenceCountUtil.touch(
      msg,
      s"Last touch point in FS2-Netty for ${msg.getClass.getSimpleName}"
    ) match {
      case i: I =>
        disp.unsafeRunAndForget(readsQueue.offer(i))

      case _ =>
        ReferenceCountUtil.safeRelease(
          msg
        ) // TODO: Netty logs if release fails, but perhaps we want to catch error and do custom logging/reporting/handling
    }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable) =
    disp.unsafeRunAndForget(readsQueue.offer(t))

  override def channelInactive(ctx: ChannelHandlerContext) =
    try {
      disp.unsafeRunAndForget(readsQueue.offer(null))
    } catch {
      case _: IllegalStateException =>
        () // sometimes we can see this due to race conditions in shutdown
    }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit =
    evt match {
      case e: E => disp.unsafeRunAndForget(eventsQueue.offer(e))
      case _ => () // TODO: probably raise error on stream...
    }

  override lazy val events: Stream[F, E] =
    Stream.fromQueueUnterminated(eventsQueue)

  override def mutatePipeline[I2, O2, E2](
    mutator: ChannelPipeline => F[Unit]
  ): F[Socket[F, I2, O2, E2]] =
    Sync[F]
      .suspend(Sync.Type.Delay)(mutator(channel.pipeline()))
      .flatMap(_ => SocketHandler[F, I2, O2, E2](disp, channel))
      .map(
        identity
      ) // TODO: why cannot compiler infer TcpSocketHandler in flatMap?
}

private object SocketHandler {

  def apply[F[_]: Async, I, O, E](
    disp: Dispatcher[F],
    channel: SocketChannel
  ): F[SocketHandler[F, I, O, E]] =
    for {
      readsQueue <- Queue.unbounded[F, AnyRef]
      eventsQueue <- Queue.unbounded[F, E]
    } yield new SocketHandler(disp, channel, readsQueue, eventsQueue)
}
