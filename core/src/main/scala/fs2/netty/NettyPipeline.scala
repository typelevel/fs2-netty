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

import cats.effect.std.Dispatcher
import cats.effect.{Async, Sync}
import cats.syntax.all._
import io.netty.buffer.ByteBuf
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{Channel, ChannelHandler, ChannelInitializer}

// TODO: account for Sharable annotation, some of these need to be an eval, and evaluated each time whereas others can be eagerly evaluated.
class NettyPipeline[F[_]: Async, I: Socket.Decoder, O, E](
  handlers: List[ChannelHandler]
)(
  dispatcher: Dispatcher[F]
) {

  // TODO: there are other interesting type of channels
  // TODO: Remember ChannelInitializer is Sharable!
  def toSocketChannelInitializer(
    cb: Socket[F, I, O, E] => F[Unit]
  ): F[ChannelInitializer[SocketChannel]] =
    toChannelInitializer[SocketChannel](cb)

  def toChannelInitializer[C <: Channel](
    cb: Socket[F, I, O, E] => F[Unit]
  ): F[ChannelInitializer[C]] = Sync[F].delay { (ch: C) =>
    {
      val p = ch.pipeline()
      ch.config().setAutoRead(false)

      handlers.foldLeft(p)((pipeline, handler) => pipeline.addLast(handler))

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
    Sync[F].delay(
      new NettyPipeline[F, ByteBuf, ByteBuf, Nothing](
        handlers = Nil
      )(dispatcher)
    )

}
