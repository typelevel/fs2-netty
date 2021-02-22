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

package fs2.netty.incudator.http

import com.comcast.ip4s.{IpAddress, SocketAddress}
import fs2.netty.Socket
import fs2.{INothing, Pipe, Stream}
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http.websocketx.WebSocketFrame

class WebSocket[F[_], U](
  underlying: Socket[F, WebSocketFrame, WebSocketFrame, Nothing]  // Delegate pattern
) extends Socket[F, WebSocketFrame, WebSocketFrame, U] {

  override def localAddress: F[SocketAddress[IpAddress]] = underlying.localAddress

  override def remoteAddress: F[SocketAddress[IpAddress]] = underlying.remoteAddress

  override def reads: Stream[F, WebSocketFrame] = underlying.reads

  // TODO: this will be aware of close frames
  override def write(output: WebSocketFrame): F[Unit] =
    underlying.write(output)

  override def writes: Pipe[F, WebSocketFrame, INothing] = underlying.writes

  override def events: Stream[F, Nothing] = underlying.events

  override def mutatePipeline[I2, O2, U2](
    mutator: ChannelPipeline => F[Unit]
  ): F[Socket[F, I2, O2, U2]] =
    underlying.mutatePipeline(mutator)
}
