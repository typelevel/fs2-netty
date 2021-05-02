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

import cats.arrow.Profunctor
import cats.syntax.all._
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelPipeline

// TODO: `I <: ReferenceCounted` to avoid type erasure. This is a very big constraint on the Netty channel, although for HTTP
//  and WS use cases this is completely ok. One alternative is scala reflections api, but will overhead be acceptable
//  along the critical code path (assuming high volume servers/clients)?
//  Think through variance of types.
trait Socket[F[_], O, I] {

  // TODO: Temporarily disabling while making Socket generic enough to test with EmbeddedChannel. Furthermore, these
  //  methods restrict Socket to be a InetChannel which isn't compatible with EmbeddedChannel. Netty also works with
  //  DomainSocketChannel and LocalChannel which have DomainSocketAddress and LocalAddress respectively(both for IPC?),
  //  not IpAddresses.
  //  Can these be provided on the server or client network resource construction rather than on the Socket?
//  def localAddress: F[SocketAddress[IpAddress]]
//  def remoteAddress: F[SocketAddress[IpAddress]]

  def reads: Stream[F, I]

  /**
    * Handlers may optionally generate events to communicate with downstream handlers. These include but not limited to
    * signals about handshake complete, timeouts, and errors.
    *
    * Some examples from Netty:
    *  - ChannelInputShutdownReadComplete
    *  - ChannelInputShutdownEvent
    *  - SslCompletionEvent
    *  - ProxyConnectionEvent
    *  - HandshakeComplete
    *  - Http2FrameStreamEvent
    *  - IdleStateEvent
    * @return
    */
  def events: Stream[F, AnyRef]

  def write(output: O): F[Unit]
  def writes: Pipe[F, O, INothing]

  def isOpen: F[Boolean]
  def isClosed: F[Boolean]
  def isDetached: F[Boolean]

  def close(): F[Unit]

  def mutatePipeline[O2, I2: Socket.Decoder](
    mutator: ChannelPipeline => F[Unit]
  ): F[Socket[F, O2, I2]]
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

  //todo Do we then define an IO instance of this?
  // Maybe we need to have a custom typeclass that also accounts for pipeline handling type C? Although contravariance
  // should handle that?
  implicit def ProfunctorInstance[F[_]]: Profunctor[Socket[F, *, *]] =
    new Profunctor[Socket[F, *, *]] {

      override def dimap[A, B, C, D](
        fab: Socket[F, A, B]
      )(f: C => A)(g: B => D): Socket[F, C, D] =
        new Socket[F, C, D] {
          override def reads: Stream[F, D] = fab.reads.map(g)

          override def events: Stream[F, AnyRef] = fab.events

          override def write(output: C): F[Unit] = fab.write(f(output))

          override def writes: Pipe[F, C, INothing] =
            _.map(f).through(fab.writes)

          override def isOpen: F[Boolean] = fab.isOpen

          override def isClosed: F[Boolean] = fab.isClosed

          override def isDetached: F[Boolean] = fab.isDetached

          override def close(): F[Unit] = fab.close()

          override def mutatePipeline[O2, I2: Decoder](
            mutator: ChannelPipeline => F[Unit]
          ): F[Socket[F, O2, I2]] = fab.mutatePipeline(mutator)
        }
    }
}
