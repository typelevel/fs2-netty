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

package fs2.netty.incudator

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.Stream
import fs2.netty.{ChannelOption, fromNettyFuture}
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.{ChannelHandler, ChannelInitializer, EventLoopGroup, ServerChannel, ChannelOption => JChannelOption}

import java.net.InetSocketAddress

final class TcpNetwork[F[_]: Async] private (
  parent: EventLoopGroup, // TODO: custom value class?
  child: EventLoopGroup,
  //  clientChannelClazz: Class[_ <: Channel],
  serverChannelClazz: Class[_ <: ServerChannel]
) {

  def server(
    host: Option[Host],
    port: Option[Port],
    options: List[ChannelOption] = Nil
  ): Resource[
    F,
    (SocketAddress[IpAddress], Stream[F, TcpSocket[F, Byte, Byte, Nothing]])
  ] = server(host, port, options, Nil)

  def server[I, O, U](
    host: Option[Host],
    port: Option[Port],
    options: List[ChannelOption] = Nil,
    handlers: List[ChannelHandler] =
      Nil // TODO: Atm completely unsafe, but will fix
  ): Resource[
    F,
    (SocketAddress[IpAddress], Stream[F, TcpSocket[F, I, O, U]])
  ] = {
    Dispatcher[F].flatMap { dispatcher =>
      Resource.suspend {
        for {
          tcpServerConnections <- Queue.unbounded[F, TcpSocket[F, I, O, U]]

          resolved <- host.traverse(_.resolve[F])

          bootstrap <- Sync[F].delay {
            val bootstrap = new ServerBootstrap
            bootstrap
              .group(parent, child)
              .option(
                JChannelOption.AUTO_READ.asInstanceOf[JChannelOption[Any]],
                false
              ) // backpressure accepting connections, not reads on any individual connection
              .channel(serverChannelClazz)
              .childHandler(new ChannelInitializer[SocketChannel] {
                override def initChannel(ch: SocketChannel): Unit = {
                  handlers.foreach(ch.pipeline().addLast(_))
                  // TODO: ...
                  dispatcher.unsafeRunAndForget {
                    TcpSocketHandler[F, I, O, U](ch)
                      .flatTap(nb => Sync[F].delay(ch.pipeline().addLast(nb)))
                      .flatMap(tcpServerConnections.offer)
                  }
                }
              })
            //              .childOption() // TODO: what child opts are there? Anything useful to communicate to injected Netty pipeline can be done through attrs...
            // TODO: log `bootstrap.config()` as info or debug or trace?
            options.foreach(opt => bootstrap.option(opt.key, opt.value))
            bootstrap
          }

          // TODO: is the right name? Bind uses the parent ELG that calla TCP accept which yields a connection to child ELG?
          tcpAcceptChannel = Sync[F] defer {
            val cf = bootstrap.bind(
              resolved.map(_.toInetAddress).orNull,
              port.map(_.value).getOrElse(0)
            )
            fromNettyFuture[F](cf.pure[F]).as(cf.channel())
          }
        } yield {
          Resource
            .make(tcpAcceptChannel) { ch =>
              fromNettyFuture[F](Sync[F].delay(ch.close())).void
            }
            .evalMap { ch =>
              Sync[F]
                .delay(
                  SocketAddress.fromInetSocketAddress(
                    ch.localAddress().asInstanceOf[InetSocketAddress]
                  )
                )
                .tupleRight(
                  Stream.repeatEval(
                    Sync[F].delay(ch.read()) *> tcpServerConnections.take
                  )
                )
            }
        }
      }
    }
  }
}

object TcpNetwork {

  def apply[F: Async](): TcpNetwork[F] = new TcpNetwork[F](
    parent = new NioEventLoopGroup(1),
    child = new NioEventLoopGroup(),
    classOf[NioServerSocketChannel]
  )

}
