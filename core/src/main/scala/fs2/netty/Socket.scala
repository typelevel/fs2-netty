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

import cats.syntax.all._
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelPipeline

// TODO: `I <: ReferenceCounted` to avoid type erasure. This is a very big constraint on the Netty channel, although for HTTP
//  and WS use cases this is completely ok. One alternative is scala reflections api, but will overhead be acceptable
//  along the critical code path (assuming high volume servers/clients)?
//  Think through variance of types.
trait Socket[F[_], I, O, +E] {

  // TODO: Temporarily disabling while making Socket generic enough to test with EmbeddedChannel. Furthermore, these
  //  methods restrict Socket to be a InetChannel which isn't compatible with EmbeddedChannel. Netty also works with
  //  DomainSocketChannel and LocalChannel which have DomainSocketAddress and LocalAddress respectively(both for IPC?),
  //  not IpAddresses.
  //  Can these be provided on the server or client network resource construction rather than on the Socket?
//  def localAddress: F[SocketAddress[IpAddress]]
//  def remoteAddress: F[SocketAddress[IpAddress]]

  def reads: Stream[F, I]

  def events: Stream[F, E]

  def write(output: O): F[Unit]
  def writes: Pipe[F, O, INothing]

  def isOpen: F[Boolean]
  def isClosed: F[Boolean]

  def close(): F[Unit]

  def mutatePipeline[I2: Socket.Decoder, O2, E2](
    mutator: ChannelPipeline => F[Unit]
  ): F[Socket[F, I2, O2, E2]]
}

object Socket {

  trait Decoder[A] {
    def decode(x: AnyRef): Either[String, A]
  }

  private[this] val ByteBufClassName = classOf[ByteBuf].getName

  implicit val ByteBufDecoder: Decoder[ByteBuf] = {
    case bb: ByteBuf => bb.asRight[String]
    case x =>
      s"pipeline error, expected $ByteBufClassName, but got ${x.getClass.getName}"
        .asLeft[ByteBuf]
  }
}
