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
package netty.pipeline.socket

import cats.effect._
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._
import cats.{Applicative, Functor}
import fs2.netty.fromNettyFuture
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.handler.flow.FlowControlHandler
import io.netty.util.ReferenceCountUtil

final class SocketHandler[F[_]: Async: Concurrent, O, I] private (
  disp: Dispatcher[F],
  private var channel: Channel,
  readsQueue: Queue[F, Option[Either[Throwable, I]]],
  eventsQueue: Queue[F, AnyRef],
  pipelineMutationSwitch: Deferred[F, Unit]
)(implicit inboundDecoder: Socket.Decoder[I])
    extends ChannelInboundHandlerAdapter
    with Socket[F, O, I] {

//  override val localAddress: F[SocketAddress[IpAddress]] =
//    Sync[F].delay(SocketAddress.fromInetSocketAddress(channel.localAddress()))
//
//  override val remoteAddress: F[SocketAddress[IpAddress]] =
//    Sync[F].delay(SocketAddress.fromInetSocketAddress(channel.remoteAddress()))

  // TODO: we can avoid Option boxing if I <: Null
  private[this] def take(poll: Poll[F]): F[Option[I]] =
    poll(readsQueue.take) flatMap {
      case None =>
        Applicative[F].pure(none[I]) // EOF marker

      case Some(Right(i)) =>
        Applicative[F].pure(i.some)

      case Some(Left(t)) =>
        t.raiseError[F, Option[I]]
    }

  private[this] val fetch: Stream[F, I] =
    Stream
      .bracketFull[F, Option[I]](poll =>
        Sync[F].delay(channel.read()) *> take(poll)
      ) { (opt, _) =>
        opt.fold(Applicative[F].unit)(i =>
          Sync[F]
            .delay(ReferenceCountUtil.safeRelease(i))
            .void // TODO: check ref count before release?
        )
      }
      .unNoneTerminate
      .interruptWhen(pipelineMutationSwitch.get.attempt)

  override lazy val reads: Stream[F, I] =
    Stream force {
      Functor[F].ifF(isOpen)(
        fetch.flatMap(i =>
          if (i == null) Stream.empty else Stream.emit(i)
        ) ++ reads,
        Stream.empty
      )
    }

  override lazy val events: Stream[F, AnyRef] =
    Stream
      .fromQueueUnterminated(eventsQueue)
      .interruptWhen(pipelineMutationSwitch.get.attempt)

  override def write(output: O): F[Unit] =
    fromNettyFuture[F](
      /*Sync[F].delay(println(s"Write ${debug(output)}")) *>*/ Sync[F].delay(
        channel.writeAndFlush(output)
      )
    ).void

  override val writes: Pipe[F, O, INothing] =
    _.evalMap(o => write(o) *> isOpen).takeWhile(bool => bool).drain

  override val isOpen: F[Boolean] =
    Sync[F].delay(channel.isOpen)

  override val isClosed: F[Boolean] = isOpen.map(bool => !bool)

  override val isDetached: F[Boolean] =
    Sync[F].delay(channel.isInstanceOf[NoopChannel])

  override def close(): F[Unit] = fromNettyFuture[F](
    Sync[F].delay(channel.close())
  ).void

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) =
    inboundDecoder.decode(
      ReferenceCountUtil.touch(
        msg,
        s"Last touch point in FS2-Netty for ${msg.getClass.getSimpleName}"
      )
    ) match {
      case Left(errorMsg) =>
        // TODO: Netty logs if release fails, but perhaps we want to catch error and do custom logging/reporting/handling
        ReferenceCountUtil.safeRelease(msg)

      case Right(i) =>
        // TODO: what's the perf impact of unsafeRunSync-only vs. unsafeRunAndForget-&-FlowControlHandler?
//        println(s"READ ${debug(msg)}")
        disp.unsafeRunAndForget(readsQueue.offer(i.asRight[Exception].some))
    }

  private def debug(x: Any) = x match {
    case bb: ByteBuf =>
      val b = bb.readByte()
      bb.resetReaderIndex()
      val arr = Array[Byte](1)
      arr(0) = b
      new String(arr)

    case _ =>
      "blah"
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable) =
    disp.unsafeRunAndForget(readsQueue.offer(t.asLeft[I].some))

  override def channelInactive(ctx: ChannelHandlerContext) =
    try {
      //TODO: Is ordering preserved?
      disp.unsafeRunAndForget(readsQueue.offer(None))
    } catch {
      case _: IllegalStateException =>
        () // sometimes we can see this due to race conditions in shutdown
    }

  override def userEventTriggered(
    ctx: ChannelHandlerContext,
    evt: AnyRef
  ): Unit =
    //TODO: Is ordering preserved? Might indeed be best to not run this handler in a separate thread pool (unless
    // netty manages ordering...which isn't likely as it should just hand off to ec) and call dispatcher manually
    // where needed. This way we can keep a thread-unsafe mutable queue.
    disp.unsafeRunAndForget(eventsQueue.offer(evt))

  override def mutatePipeline[O2, I2: Socket.Decoder](
    mutator: ChannelPipeline => F[Unit]
  ): F[Socket[F, O2, I2]] =
    for {
      // TODO: Edge cases aren't fully tested
      _ <- pipelineMutationSwitch.complete(
        ()
      ) // shutdown the events and reads streams
      oldChannel = channel // Save reference, as we first stop socket processing
      _ <- Sync[F].delay {
        channel = new NoopChannel(channel)
      } // shutdown writes
      _ <- Sync[F].delay(
        oldChannel.pipeline().removeLast()
      ) //remove SocketHandler
      _ <- Sync[F].delay(
        oldChannel.pipeline().removeLast()
      ) //remove FlowControlHandler
      /*
      TODO: Above may dump remaining messages into fireChannelRead, do we care about those messages? Should we
       signal up that this happened? Probably should as certain apps may care about a peer not behaving according to
       the expected protocol. In this case, we add a custom handler to capture those messages, then either:
        - raiseError on the new reads stream, or
        - set a Signal
       Also need to think through edge case where Netty is concurrently calling channel read vs. this manipulating
       pipeline. Maybe protocols need to inform this layer about when exactly to transition.
       */
      _ <- mutator(oldChannel.pipeline())
      sh <- SocketHandler[F, O2, I2](disp, oldChannel)
      // TODO: pass a name for debugging purposes?
      _ <- Sync[F].delay(
        oldChannel.pipeline().addLast(new FlowControlHandler(false))
      )
      _ <- Sync[F].delay(oldChannel.pipeline().addLast(sh))
    } yield sh

  // not to self: if we want to schedule an action to be done when channel is closed, can also do `ctx.channel.closeFuture.addListener`
}

object SocketHandler {

  def apply[F[_]: Async: Concurrent, O, I: Socket.Decoder](
    disp: Dispatcher[F],
    channel: Channel
  ): F[SocketHandler[F, O, I]] =
    for {
      readsQueue <- Queue.unbounded[F, Option[Either[Throwable, I]]]
      eventsQueue <- Queue.unbounded[F, AnyRef]
      pipelineMutationSwitch <- Deferred[F, Unit]
    } yield new SocketHandler(
      disp,
      channel,
      readsQueue,
      eventsQueue,
      pipelineMutationSwitch
    )

}
