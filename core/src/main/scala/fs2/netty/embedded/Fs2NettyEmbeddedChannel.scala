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
package netty.embedded

import cats.effect.{Async, Sync}
import cats.implicits._
import fs2.netty.embedded.Fs2NettyEmbeddedChannel.Encoder
import fs2.netty.{NettyChannelInitializer, Socket}
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.embedded.EmbeddedChannel

/**
  * Better, safer, and clearer api for testing channels
  * For use in tests only.
  * @param underlying
  * @param F
  * @tparam F
  */
final case class Fs2NettyEmbeddedChannel[F[_]] private (
  underlying: EmbeddedChannelWithAutoRead
)(implicit
  F: Sync[F]
) {

  // TODO: write examples (a spec?) for these

  def writeAllInboundWithoutFlush[A](
    a: A*
  )(implicit encoder: Encoder[A]): F[Unit] =
    for {
      encodedObjects <- F.delay(a.map(encoder.encode))
      _ <- encodedObjects.traverse(bb =>
        F.delay(underlying.writeOneInbound(bb))
      ) // returns channelFutures
    } yield ()

  /**
    * @param a
    * @param encoder
    * @tparam A
    * @return `true` if the write operation did add something to the inbound buffer
    */
  def writeAllInboundThenFlushThenRunAllPendingTasks[A](a: A*)(implicit
    encoder: Encoder[A]
  ): F[Boolean] = for {
    encodedObjects <- F.delay(a.map(encoder.encode))
    areMsgsAdded <- F.delay(
      underlying.writeInboundFixed(encodedObjects: _*)
    ) // areByteBufsAddedToUnhandledBuffer? onUnhandledInboundMessage
  } yield areMsgsAdded

  def flushInbound(): F[Unit] = F.delay(underlying.flushInbound()).void

  def isOpen: F[Boolean] = F.pure(underlying.isOpen)

  def isClosed: F[Boolean] = F.pure(!underlying.isOpen)

  def close(): F[Unit] = F.delay(underlying.close()).void
}

object Fs2NettyEmbeddedChannel {

  def apply[F[_], I, O, E](
    initializer: NettyChannelInitializer[F, I, O, E]
  )(implicit F: Async[F]): F[(Fs2NettyEmbeddedChannel[F], Socket[F, I, O, E])] =
    for {
      channel <- F.delay(
        new EmbeddedChannelWithAutoRead()
      ) // With FlowControl/Dispatcher fixes, EmbeddedChannelWithAutoRead might not be needed after all.
      socket <- F.async[Socket[F, I, O, E]] { cb =>
        initializer
          .toChannelInitializer[EmbeddedChannel] { socket =>
            F.delay(cb(socket.asRight[Throwable]))
          }
          .flatMap { initializer =>
            F.delay(channel.pipeline().addFirst(initializer)) *> F.delay(
              channel.runPendingTasks()
            )
          }
          .as[Option[F[Unit]]](None)
      }
    } yield (new Fs2NettyEmbeddedChannel[F](channel), socket)

  // TODO: Functor and contramap
  trait Encoder[A] {
    def encode(a: A): ByteBuf
  }

  object CommonEncoders {
    implicit val byteBufEncoder: Encoder[ByteBuf] = identity

    implicit val byteArrayEncoder: Encoder[Array[Byte]] = (a: Array[Byte]) =>
      Unpooled.wrappedBuffer(a)

    implicit val byteEncoder: Encoder[Byte] = (a: Byte) =>
      Unpooled.buffer(1, 1).writeByte(a.toInt)

    implicit val stringEncoder: Encoder[String] = (str: String) =>
      byteArrayEncoder.encode(str.getBytes)

//    implicit def listEncoder[A](implicit decoder: Decoder[A]): Encoder[List[A]] = (list: List[A]) =>
//      list.map()
  }
}
