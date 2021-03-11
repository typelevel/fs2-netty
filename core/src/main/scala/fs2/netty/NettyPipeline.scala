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

package fs2.netty

import cats.Eval
import cats.effect.std.Dispatcher
import cats.effect.{Async, Sync}
import cats.syntax.all._
import io.netty.buffer.ByteBuf
import io.netty.channel.{Channel, ChannelHandler, ChannelHandlerAdapter, ChannelInitializer}
import io.netty.handler.flow.FlowControlHandler

class NettyPipeline[F[_]: Async, I: Socket.Decoder, O, E] private (
  handlers: List[Eval[ChannelHandler]]
)(
  dispatcher: Dispatcher[F]
) extends NettyChannelInitializer[F, I, O, E] {

  // TODO: there are other interesting type of channels
  // TODO: Remember ChannelInitializer is Sharable!
  override def toChannelInitializer[C <: Channel](
    cb: Socket[F, I, O, E] => F[Unit]
  ): F[ChannelInitializer[C]] = Sync[F].delay { (ch: C) =>
    {
      val p = ch.pipeline()
      ch.config().setAutoRead(false)

      handlers
        .map(_.value)
        .foldLeft(p)((pipeline, handler) => pipeline.addLast(handler))
        // `channelRead` on ChannelInboundHandler's may get invoked more than once despite autoRead being turned off
        // and handler calling read to control read rate, i.e. backpressure. Netty's solution is to use `FlowControlHandler`.
        .addLast(new FlowControlHandler(false))

      dispatcher.unsafeRunAndForget {
        // TODO: read up on CE3 Dispatcher, how is it different than Context Switch? Is this taking place async? Also is cats.effect.Effect removed in CE3?
        SocketHandler[F, I, O, E](dispatcher, ch)
          .flatTap(h =>
            Sync[F].delay(p.addLast(h))
          ) // TODO: pass EventExecutorGroup
          .flatMap(cb)
        //TODO: Wonder if cb should be invoked on handlerAdded in SocketHandler? Technically, Socket isn't
        // "fully active"; SocketHandler is in the pipeline but is marked with ADD_PENDING status (whatever that means,
        // maybe it's ok). Need to work out expectation of callers. And if this is addLast is called from a different
        // thread, then handlerAdded will be scheduled by Netty to execute in the future.
      }
    }
  }
}

object NettyPipeline {

  def apply[F[_]: Async](
    dispatcher: Dispatcher[F]
  ): F[NettyPipeline[F, ByteBuf, ByteBuf, Nothing]] =
    apply(dispatcher, handlers = Nil)

  def apply[F[_]: Async, I: Socket.Decoder, O, E](
    dispatcher: Dispatcher[F],
    handlers: List[Eval[ChannelHandler]]
  ): F[NettyPipeline[F, I, O, E]] =
    Sync[F].delay(
      new NettyPipeline[F, I, O, E](
        memoizeSharableHandlers(handlers)
      )(dispatcher)
    )

  /*
  Netty will throw an exception if Sharable handler is added to more than one channel.
   */
  private[this] def memoizeSharableHandlers[E, O, I: Socket.Decoder, F[
    _
  ]: Async](handlers: List[Eval[ChannelHandler]]) =
    handlers.map(eval =>
      eval.flatMap {
        case adapter: ChannelHandlerAdapter if adapter.isSharable =>
          eval.memoize
        case _ =>
          eval
      }
    )
}
