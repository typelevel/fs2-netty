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

import cats.effect.{Async, Concurrent, Resource, Sync}
import cats.effect.std.{Dispatcher, Queue}
import cats.syntax.all._

import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.channel.{Channel, ChannelInitializer, ChannelOption, EventLoopGroup, ServerChannel}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.channel.socket.SocketChannel

import java.net.{InetAddress, InetSocketAddress}

final class Network[F[_]: Async] private (
    parent: EventLoopGroup,
    child: EventLoopGroup,
    clientChannelClazz: Class[_ <: Channel],
    serverChannelClazz: Class[_ <: ServerChannel]) {

  def client(addr: InetSocketAddress,
      reuseAddress: Boolean = true,
      keepAlive: Boolean = false,
      noDelay: Boolean = false)
      : Resource[F, Socket[F]] =
    Dispatcher[F] flatMap { disp =>
      Resource suspend {
        Concurrent[F].deferred[Socket[F]] flatMap { d =>
          Sync[F] delay {
            val bootstrap = new Bootstrap
            bootstrap.group(child)
              .channel(clientChannelClazz)
              .option(ChannelOption.AUTO_READ.asInstanceOf[ChannelOption[Any]], false)   // backpressure
              .option(ChannelOption.SO_REUSEADDR.asInstanceOf[ChannelOption[Any]], reuseAddress)
              .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], keepAlive)
              .option(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], noDelay)
              .handler(initializer(disp)(d.complete(_).void))

            val connectChannel = Sync[F] defer {
              val cf = bootstrap.connect(addr)
              fromNettyFuture[F](cf.pure[F]).as(cf.channel())
            }

            Resource.make(connectChannel <* d.get)(ch => fromNettyFuture(Sync[F].delay(ch.close())).void).evalMap(_ => d.get)
          }
        }
      }
    }

  def server(
      addr: InetSocketAddress,
      reuseAddress: Boolean = true,
      keepAlive: Boolean = false,
      noDelay: Boolean = false)
      : Stream[F, Socket[F]] = {
    val connection = Stream resource {
      serverResource(
        addr.getAddress(),
        Some(addr.getPort()),
        reuseAddress = reuseAddress,
        keepAlive = keepAlive,
        noDelay = noDelay)
    }

    connection.flatMap(_._2)
  }

  def serverResource(
      host: InetAddress,
      port: Option[Int],
      reuseAddress: Boolean = true,
      keepAlive: Boolean = false,
      noDelay: Boolean = false)
      : Resource[F, (InetSocketAddress, Stream[F, Socket[F]])] =
    Dispatcher[F] flatMap { disp =>
      Resource suspend {
        Queue.synchronous[F, Socket[F]] flatMap { sockets =>
          Sync[F] delay {
            val bootstrap = new ServerBootstrap
            bootstrap.group(parent, child)
              .option(ChannelOption.AUTO_READ.asInstanceOf[ChannelOption[Any]], false)   // backpressure
              .option(ChannelOption.SO_REUSEADDR.asInstanceOf[ChannelOption[Any]], reuseAddress)
              .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], keepAlive)
              .option(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], noDelay)
              .channel(serverChannelClazz)
              .childHandler(initializer(disp)(sockets.offer))

            val connectChannel = Sync[F] defer {
              val cf = bootstrap.bind(new InetSocketAddress(host, port.getOrElse(0)))
              fromNettyFuture[F](cf.pure[F]).as(cf.channel())
            }

            val connection = Resource.make(connectChannel) { ch =>
              fromNettyFuture[F](Sync[F].delay(ch.close())).void
            }

            connection evalMap { ch =>
              Sync[F].delay(ch.localAddress().asInstanceOf[InetSocketAddress]).tupleRight(
                Stream.repeatEval(Sync[F].delay(ch.read()) *> sockets.take))
            }
          }
        }
      }
    }

  private[this] def initializer(
      disp: Dispatcher[F])(
      result: Socket[F] => F[Unit])
      : ChannelInitializer[SocketChannel] =
    new ChannelInitializer[SocketChannel] {
      def initChannel(ch: SocketChannel) = {
        val p = ch.pipeline()
        ch.config().setAutoRead(false)

        disp unsafeRunSync {
          val handlerF = Sync[F].delay(new SocketHandler[F](ch))
          handlerF.flatMap(s => Sync[F].delay(p.addLast(s)) *> result(s))
        }
      }
    }
}

object Network {

  // TODO detect niouring/epoll/kpoll
  private[this] val EventLoopConstr = classOf[NioEventLoopGroup].getDeclaredConstructor(classOf[Int])
  private[this] val ServerChannelClazz = classOf[NioServerSocketChannel]
  private[this] val ClientChannelClazz = classOf[NioSocketChannel]

  def apply[F[_]: Async]: Resource[F, Network[F]] = {
    // TODO configure threads
    val instantiate = Sync[F] delay {
      val result = EventLoopConstr.newInstance(new Integer(1))
      result.asInstanceOf[EventLoopGroup]
    }

    val instantiateR = Resource.make(instantiate)(elg => fromNettyFuture[F](Sync[F].delay(elg.shutdownGracefully())).void)

    (instantiateR, instantiateR).mapN(new Network[F](_, _, ClientChannelClazz, ServerChannelClazz))
  }
}
