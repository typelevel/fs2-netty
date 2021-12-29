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

import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}

import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.channel.{Channel, ChannelInitializer, ChannelOption => JChannelOption, EventLoopGroup, ServerChannel}
import io.netty.channel.socket.SocketChannel

import java.net.InetSocketAddress
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

final class Network[F[_]: Async] private (
    parent: EventLoopGroup,
    child: EventLoopGroup,
    clientChannelClazz: Class[_ <: Channel],
    serverChannelClazz: Class[_ <: ServerChannel]) {

  def client(
      addr: SocketAddress[Host],
      options: List[ChannelOption] = Nil)
      : Resource[F, Socket[F]] =
    Dispatcher[F] flatMap { disp =>
      Resource suspend {
        Concurrent[F].deferred[Socket[F]] flatMap { d =>
          addr.host.resolve[F] flatMap { resolved =>
            Sync[F] delay {
              val bootstrap = new Bootstrap
              bootstrap.group(child)
                .channel(clientChannelClazz)
                .option(JChannelOption.AUTO_READ.asInstanceOf[JChannelOption[Any]], false)   // backpressure
                .handler(initializer(disp)(d.complete(_).void))

              options.foreach(opt => bootstrap.option(opt.key, opt.value))

              val connectChannel = Sync[F] defer {
                val cf = bootstrap.connect(resolved.toInetAddress, addr.port.value)
                fromNettyFuture[F](cf.pure[F]).as(cf.channel())
              }

              Resource.make(connectChannel <* d.get)(ch => fromNettyFuture(Sync[F].delay(ch.close())).void).evalMap(_ => d.get)
            }
          }
        }
      }
    }

  def server(
      host: Option[Host],
      port: Port,
      options: List[ChannelOption] = Nil)
      : Stream[F, Socket[F]] =
    Stream.resource(serverResource(host, Some(port), options)).flatMap(_._2)

  def serverResource(
      host: Option[Host],
      port: Option[Port],
      options: List[ChannelOption] = Nil)
      : Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F]])] =
    Dispatcher[F] flatMap { disp =>
      Resource suspend {
        Queue.unbounded[F, Socket[F]] flatMap { sockets =>
          host.traverse(_.resolve[F]) flatMap { resolved =>
            Sync[F] delay {
              val bootstrap = new ServerBootstrap
              bootstrap.group(parent, child)
                // TODO: does netty override this? `ServerBootstrap.init` via `bind` override schedules a future to set to autoread to false
                .option(JChannelOption.AUTO_READ.asInstanceOf[JChannelOption[Any]], false) // backpressure
                .channel(serverChannelClazz)
                .childHandler(initializer(disp)(sockets.offer))

              options.foreach(opt => bootstrap.option(opt.key, opt.value))

              val connectChannel = Sync[F] defer {
                val cf = bootstrap.bind(
                  resolved.map(_.toInetAddress).orNull,
                  port.map(_.value).getOrElse(0))
                fromNettyFuture[F](cf.pure[F]).as(cf.channel())
              }

              val connection = Resource.make(connectChannel) { ch =>
                fromNettyFuture[F](Sync[F].delay(ch.close())).void
              }

              connection evalMap { ch =>
                Sync[F].delay(SocketAddress.fromInetSocketAddress(ch.localAddress().asInstanceOf[InetSocketAddress])).tupleRight(
                  Stream.repeatEval(Sync[F].delay(ch.read()) *> sockets.take))
              }
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

        disp unsafeRunAndForget {
          SocketHandler[F](disp, ch) flatMap { s =>
            Sync[F].delay(p.addLast(s)) *> result(s)
          }
        }
      }
    }
}

object Network {

  private[this] val (eventLoopClazz, serverChannelClazz, clientChannelClazz) = {
    val (e, s, c) = uring().orElse(epoll()).orElse(kqueue()).getOrElse(nio())

    (e, s.asInstanceOf[Class[_ <: ServerChannel]], c.asInstanceOf[Class[_ <: Channel]])
  }

  def apply[F[_]: Async]: Resource[F, Network[F]] = {
    // TODO configure threads
    def instantiate(name: String) = Sync[F] delay {
      val constr = eventLoopClazz.getDeclaredConstructor(classOf[Int], classOf[ThreadFactory])
      val result = constr.newInstance(new Integer(1), new ThreadFactory {
        private val ctr = new AtomicInteger(0)
        def newThread(r: Runnable): Thread = {
          val t = new Thread(r)
          t.setDaemon(true)
          t.setName(s"fs2-netty-$name-io-worker-${ctr.getAndIncrement()}")
          t.setPriority(Thread.MAX_PRIORITY)
          t
        }
      })

      result.asInstanceOf[EventLoopGroup]
    }

    def instantiateR(name: String) =
      Resource.make(instantiate(name)) { elg =>
        fromNettyFuture[F](Sync[F].delay(elg.shutdownGracefully())).void
      }

    (instantiateR("server"), instantiateR("client")) mapN { (server, client) =>
      try {
        val meth = eventLoopClazz.getDeclaredMethod("setIoRatio", classOf[Int]) // TODO: this method is set to be depcrecated in the future releases
        meth.invoke(server, new Integer(90)) // TODO tweak this a bit more; 100 was worse than 50 and 90 was a dramatic step up from both
        meth.invoke(client, new Integer(90))
      } catch {
        case _: Exception => ()
      }

      new Network[F](server, client, clientChannelClazz, serverChannelClazz)
    }
  }

  //TODO: Why not use the Netty methods 
  private[this] def uring() =
    try {
      if (sys.props.get("fs2.netty.use.io_uring").map(_.toBoolean).getOrElse(false)) {
        Class.forName("io.netty.incubator.channel.uring.IOUringEventLoop")

        Some((
          Class.forName("io.netty.incubator.channel.uring.IOUringEventLoopGroup"),
          Class.forName("io.netty.incubator.channel.uring.IOUringServerSocketChannel"),
          Class.forName("io.netty.incubator.channel.uring.IOUringSocketChannel")))
      } else {
        None
      }
    } catch {
      case _: Throwable => None
    }

  private[this] def epoll() =
    try {
      Class.forName("io.netty.channel.epoll.EpollEventLoop")

      Some((
        Class.forName("io.netty.channel.epoll.EpollEventLoopGroup"),
        Class.forName("io.netty.channel.epoll.EpollServerSocketChannel"),
        Class.forName("io.netty.channel.epoll.EpollSocketChannel")))
    } catch {
      case _: Throwable => None
    }

  private[this] def kqueue() =
    try {
      Class.forName("io.netty.channel.kqueue.KQueueEventLoop")

      Some((
        Class.forName("io.netty.channel.kqueue.KQueueEventLoopGroup"),
        Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel"),
        Class.forName("io.netty.channel.kqueue.KQueueSocketChannel")))
    } catch {
      case _: Throwable => None
    }

  private[this] def nio() =
    (
      Class.forName("io.netty.channel.nio.NioEventLoopGroup"),
      Class.forName("io.netty.channel.socket.nio.NioServerSocketChannel"),
      Class.forName("io.netty.channel.socket.nio.NioSocketChannel"))
}
