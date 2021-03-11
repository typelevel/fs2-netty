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
import cats.effect.{Async, Concurrent, Deferred, Poll, Sync}
import cats.syntax.all._
import cats.{Applicative, Functor}
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.util.ReferenceCountUtil

private final class SocketHandler[F[_]: Async: Concurrent, I, O, +E](
  disp: Dispatcher[F],
  private var channel: Channel,
  readsQueue: Queue[F, Option[Either[Throwable, I]]],
  eventsQueue: Queue[F, E],
  pipelineMutationSwitch: Deferred[F, Unit]
)(implicit inboundDecoder: Socket.Decoder[I])
    extends ChannelInboundHandlerAdapter
    with Socket[F, I, O, E] {

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
          Sync[F].delay(ReferenceCountUtil.safeRelease(i)).void // TODO: check ref count before release?
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

  override lazy val events: Stream[F, E] =
    Stream
      .fromQueueUnterminated(eventsQueue)
      .interruptWhen(pipelineMutationSwitch.get.attempt)

  override def write(output: O): F[Unit] =
    fromNettyFuture[F](
      /* Sync[F].delay(println(debug(output))) *> */ Sync[F].delay(
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

  // TODO: Even with a single channel.read() call, channelRead may get invoked more than once! Netty's "solution"
  //  is to use FlowControlHandler. Why isn't that the default Netty behavior?!
  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) = {
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
        // TODO: what's the perf impact of unsafeRunSync vs unsafeRunAndForget?
        //  FlowControlHandler & unsafeRunAndForget vs. unsafeRunSync-only?
        //  Review for other Netty methods as well.
        disp.unsafeRunSync(readsQueue.offer(i.asRight[Exception].some))
    }
  }

  private def debug(x: Any) = x match {
    case bb: ByteBuf =>
      val b = bb.readByte()
      bb.resetReaderIndex()
      val arr = Array[Byte](1)
      arr(0) = b
      new String(arr)

    case _ =>
      ""
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable) =
    disp.unsafeRunAndForget(readsQueue.offer(t.asLeft[I].some))

  override def channelInactive(ctx: ChannelHandlerContext) =
    try {
      disp.unsafeRunAndForget(readsQueue.offer(None))
    } catch {
      case _: IllegalStateException =>
        () // sometimes we can see this due to race conditions in shutdown
    }

  override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit =
    evt match {
      case e: E => disp.unsafeRunAndForget(eventsQueue.offer(e))
      case _ => () // TODO: probably raise error on stream...
    }

  override def mutatePipeline[I2: Socket.Decoder, O2, E2](
    mutator: ChannelPipeline => F[Unit]
  ): F[Socket[F, I2, O2, E2]] =
    for {
      _ <- pipelineMutationSwitch.complete(
        ()
      ) // shutdown the events and reads streams
      oldChannel = channel // Save reference, as we first stop socket processing
      _ <- Sync[F].delay {
        channel = new NoopChannel(channel)
      } // shutdown writes
      // TODO: what if queues have elements in them? E.g. Netty is concurrently calling channel read. Protocols should disallow this for the most part.
      _ <- Sync[F].delay(oldChannel.pipeline().removeLast())
      _ <- mutator(oldChannel.pipeline())
      sh <- SocketHandler[F, I2, O2, E2](disp, oldChannel)
      _ <- Sync[F].delay(
        oldChannel.pipeline().addLast(sh)
      ) // TODO: I feel like we should pass a name for debugging purposes...?!
    } yield sh

  // not to self: if we want to schedule an action to be done when channel is closed, can also do `ctx.channel.closeFuture.addListener`
}

private object SocketHandler {

  def apply[F[_]: Async: Concurrent, I: Socket.Decoder, O, E](
    disp: Dispatcher[F],
    channel: Channel
  ): F[SocketHandler[F, I, O, E]] =
    for {
      readsQueue <- Queue.unbounded[F, Option[Either[Throwable, I]]]
      eventsQueue <- Queue.unbounded[F, E]
      pipelineMutationSwitch <- Deferred[F, Unit]
    } yield new SocketHandler(
      disp,
      channel,
      readsQueue,
      eventsQueue,
      pipelineMutationSwitch
    )

}
