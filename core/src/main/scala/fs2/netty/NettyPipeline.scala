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

class NettyPipeline[F[_]: Async, O, I: Socket.Decoder] private (
  handlers: List[Eval[ChannelHandler]]
)(
  dispatcher: Dispatcher[F]
) extends NettyChannelInitializer[F, O, I] {

  // TODO: there are other interesting type of channels
  // TODO: Remember ChannelInitializer is Sharable!
  override def toChannelInitializer[C <: Channel](
    cb: Socket[F, O, I] => F[Unit]
  ): F[ChannelInitializer[C]] = Sync[F].delay { (ch: C) =>
    {
      val p = ch.pipeline()
      ch.config().setAutoRead(false)

      handlers
        .map(_.value)
        .foldLeft(p)((pipeline, handler) => pipeline.addLast(handler))
        /* `channelRead` on ChannelInboundHandler's may get invoked more than once despite autoRead being turned off
         and handler calling read to control read rate, i.e. backpressure. Netty's solution is to use `FlowControlHandler`.
         Below from https://stackoverflow.com/questions/45887006/how-to-ensure-channelread-called-once-after-each-read-in-netty:
         | Also note that most decoders will automatically perform a read even if you have AUTO_READ=false since they
         | need to read enough data in order to yield at least one message to subsequent (i.e. your) handlers... but
         | after they yield a message, they won't auto-read from the socket again.
         */
        .addLast(new FlowControlHandler(false))

      dispatcher.unsafeRunAndForget {
        // TODO: read up on CE3 Dispatcher, how is it different than Context Switch? Is this taking place async? Also is cats.effect.Effect removed in CE3?
        SocketHandler[F, O, I](dispatcher, ch)
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
  ): F[NettyPipeline[F, ByteBuf, ByteBuf]] =
    apply(dispatcher, handlers = Nil)

  def apply[F[_]: Async, O, I: Socket.Decoder](
    dispatcher: Dispatcher[F],
    handlers: List[Eval[ChannelHandler]]
  ): F[NettyPipeline[F, O, I]] =
    Sync[F].delay(
      new NettyPipeline[F, O, I](
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
