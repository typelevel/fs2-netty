/*
 * Copyright 2020 Daniel Spiewak
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

import cats.effect.{Async, Resource, Sync}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.{ChannelInitializer, ChannelOption, EventLoopGroup, ServerChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.SocketChannel

import java.net.InetSocketAddress

final class Network[F[_]: Async] private (
    parent: EventLoopGroup,
    child: EventLoopGroup,
    channelClazz: Class[_ <: ServerChannel]) {

  def server(addr: InetSocketAddress, reuseAddress: Boolean = true, keepAlive: Boolean = false, noDelay: Boolean = false): Stream[F, Socket[F]] =
    Stream.resource(Dispatcher[F]) flatMap { disp =>
      Stream force {
        Queue.synchronous[F, Socket[F]] map { sockets =>
          val server = Stream force {
            Sync[F] delay {
              val bootstrap = new ServerBootstrap
              bootstrap.group(parent, child)
                .option(ChannelOption.AUTO_READ.asInstanceOf[ChannelOption[Any]], false)   // backpressure
                .option(ChannelOption.SO_REUSEADDR.asInstanceOf[ChannelOption[Any]], reuseAddress)
                .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], keepAlive)
                .option(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], noDelay)
                .channel(channelClazz)
                .childHandler(new ChannelInitializer[SocketChannel] {
                  def initChannel(ch: SocketChannel) = {
                    val p = ch.pipeline()

                    disp unsafeRunSync {
                      val handlerF = Queue.synchronous[F, ByteBuf].map(new SocketHandler[F](disp, ch, _))
                      handlerF flatMap { s =>
                        Sync[F].delay(p.addLast(s)) *> sockets.offer(s)
                      }
                    }
                  }
                })

              val f = bootstrap.bind(addr)

              Stream.bracket(fromNettyFuture[F](f.pure[F])) { _ =>
                fromNettyFuture[F](Sync[F].delay(f.channel().close())).void
              }
            }
          }

          server *> Stream.repeatEval(sockets.take)
        }
      }
    }
}

object Network {

  // TODO detect niouring/epoll/kpoll
  private[this] val EventLoopConstr = classOf[NioEventLoopGroup].getDeclaredConstructor(classOf[Int])
  private[this] val ChannelClazz = classOf[NioServerSocketChannel]

  def apply[F[_]: Async]: Resource[F, Network[F]] = {
    // TODO configure threads
    val instantiate = Sync[F].delay(EventLoopConstr.newInstance(1).asInstanceOf[EventLoopGroup])
    val instantiateR = Resource.make(instantiate)(elg => fromNettyFuture[F](Sync[F].delay(elg.shutdownGracefully())).void)

    (instantiateR, instantiateR).mapN(new Network[F](_, _, ChannelClazz))
  }
}
