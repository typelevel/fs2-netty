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

import cats.effect.std.{Dispatcher, Queue}
import cats.effect.{Async, Concurrent, Resource, Sync}
import cats.syntax.all._
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import fs2.netty.pipeline.NettyPipeline
import fs2.netty.pipeline.socket.Socket
import io.netty.bootstrap.{Bootstrap, ServerBootstrap}
import io.netty.buffer.ByteBuf
import io.netty.channel.{Channel, EventLoopGroup, ServerChannel, ChannelOption => JChannelOption}

import java.net.InetSocketAddress
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

// TODO: Do we need to distinguish between TCP (connection based network) and UDP (connection-less network)?
final class Network[F[_]: Async] private (
  parent: EventLoopGroup, // TODO: custom value class?
  child: EventLoopGroup,
  clientChannelClazz: Class[_ <: Channel],
  serverChannelClazz: Class[_ <: ServerChannel]
) {

  def client(
    addr: SocketAddress[Host],
    options: List[ChannelOption]
  ): Resource[F, Socket[F, ByteBuf, ByteBuf]] =
    for {
      disp <- Dispatcher[F]
      pipeline <- Resource.eval(NettyPipeline(disp))
      c <- client(addr, pipeline, options)
    } yield c

  def client[O, I](
    addr: SocketAddress[Host],
    pipelineInitializer: NettyChannelInitializer[F, O, I],
    options: List[ChannelOption]
  ): Resource[F, Socket[F, O, I]] =
    Resource.suspend {
      for {
        futureSocket <- Concurrent[F].deferred[Socket[F, O, I]]

        initializer <- pipelineInitializer.toSocketChannelInitializer(
          futureSocket.complete(_).void
        )

        resolvedHost <- addr.host.resolve[F]

        bootstrap <- Sync[F].delay {
          val bootstrap = new Bootstrap
          bootstrap
            .group(child)
            .channel(clientChannelClazz)
            .option(
              JChannelOption.AUTO_READ.asInstanceOf[JChannelOption[Any]],
              false
            ) // backpressure TODO: backpressure creating the connection or is this reads?
            .handler(initializer)

          options.foreach(opt => bootstrap.option(opt.key, opt.value))
          bootstrap
        }

        // TODO: Log properly as info, debug, or trace. Or send as an event to another stream. Maybe the whole network could have an event stream.
        _ <- Sync[F].delay(println(bootstrap.config()))

        connectChannel = Sync[F] defer {
          val cf =
            bootstrap.connect(resolvedHost.toInetAddress, addr.port.value)
          fromNettyFuture[F](cf.pure[F]).as(cf.channel())
        }
      } yield Resource
        .make(connectChannel <* futureSocket.get)(ch =>
          fromNettyFuture(Sync[F].delay(ch.close())).void
        )
        .evalMap(_ => futureSocket.get)
    }

  //TODO: Add back default args for opts, removed to fix compilation error for overloaded method
  def server(
    host: Option[Host],
    port: Port,
    options: List[ChannelOption]
  ): Stream[F, Socket[F, ByteBuf, ByteBuf]] =
    Stream.resource(serverResource(host, Some(port), options)).flatMap(_._2)

  // TODO: maybe here it's nicer to have the I first then O?, or will that be confusing if Socket has reversed order?
  def server[O, I: Socket.Decoder](
    host: Option[Host],
    port: Port,
    pipelineInitializer: NettyChannelInitializer[F, O, I],
    options: List[ChannelOption]
  ): Stream[F, Socket[F, O, I]] =
    Stream
      .resource(
        serverResource[O, I](host, Some(port), pipelineInitializer, options)
      )
      .flatMap(_._2)

  def serverResource(
    host: Option[Host],
    port: Option[Port],
    options: List[ChannelOption]
  ): Resource[
    F,
    (SocketAddress[IpAddress], Stream[F, Socket[F, ByteBuf, ByteBuf]])
  ] =
    for {
      dispatcher <- Dispatcher[F]
      pipeline <- Resource.eval(NettyPipeline[F](dispatcher))
      sr <- serverResource(host, port, pipeline, options)
    } yield sr

  def serverResource[O, I: Socket.Decoder](
    host: Option[Host],
    port: Option[Port],
    pipelineInitializer: NettyChannelInitializer[F, O, I],
    options: List[ChannelOption]
  ): Resource[F, (SocketAddress[IpAddress], Stream[F, Socket[F, O, I]])] =
    Resource suspend {
      for {
        clientConnections <- Queue.unbounded[F, Socket[F, O, I]]

        resolvedHost <- host.traverse(_.resolve[F])

        socketInitializer <- pipelineInitializer.toSocketChannelInitializer(
          clientConnections.offer
        )

        bootstrap <- Sync[F] delay {
          val bootstrap = new ServerBootstrap
          bootstrap
            .group(parent, child)
            .option(
              JChannelOption.AUTO_READ.asInstanceOf[JChannelOption[Any]],
              false
            ) // backpressure for accepting connections, not reads on any individual connection
            //.childOption() TODO: Any useful ones?
            .channel(serverChannelClazz)
            .childHandler(socketInitializer)

          options.foreach(opt => bootstrap.option(opt.key, opt.value))
          bootstrap
        }

        // TODO: Log properly as info, debug, or trace. Also can print localAddress
        _ <- Sync[F].delay(println(bootstrap.config()))

        // TODO: is the right name? Bind uses the parent ELG that calla TCP accept which yields a connection to child ELG?
        tcpAcceptChannel = Sync[F] defer {
          val cf = bootstrap.bind(
            resolvedHost.map(_.toInetAddress).orNull,
            port.map(_.value).getOrElse(0)
          )
          fromNettyFuture[F](cf.pure[F]).as(cf.channel())
        }
      } yield Resource
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
                Sync[F].delay(ch.read()) *> clientConnections.take
              )
            )
        }
    }

  implicit val decoder: Socket.Decoder[Byte] = new Socket.Decoder[Byte] {
    override def decode(x: AnyRef): Either[String, Byte] = ???
  }
}

object Network {

  private[this] val (eventLoopClazz, serverChannelClazz, clientChannelClazz) = {
    val (e, s, c) = uring().orElse(epoll()).orElse(kqueue()).getOrElse(nio())

    (
      e,
      s.asInstanceOf[Class[_ <: ServerChannel]],
      c.asInstanceOf[Class[_ <: Channel]]
    )
  }

  def apply[F[_]: Async]: Resource[F, Network[F]] = {
    // TODO configure threads
    def instantiate(name: String) = Sync[F] delay {
      val constr = eventLoopClazz.getDeclaredConstructor(
        classOf[Int],
        classOf[ThreadFactory]
      )
      val result = constr.newInstance(
        new Integer(1),
        new ThreadFactory {
          private val ctr = new AtomicInteger(0)
          def newThread(r: Runnable): Thread = {
            val t = new Thread(r)
            t.setDaemon(true)
            t.setName(s"fs2-netty-$name-io-worker-${ctr.getAndIncrement()}")
            t.setPriority(Thread.MAX_PRIORITY)
            t
          }
        }
      )

      result.asInstanceOf[EventLoopGroup]
    }

    def instantiateR(name: String) =
      Resource.make(instantiate(name)) { elg =>
        fromNettyFuture[F](Sync[F].delay(elg.shutdownGracefully())).void
      }

    (instantiateR("server"), instantiateR("client")) mapN { (server, client) =>
      try {
        val meth = eventLoopClazz.getDeclaredMethod("setIoRatio", classOf[Int])
        meth.invoke(
          server,
          new Integer(90)
        ) // TODO tweak this a bit more; 100 was worse than 50 and 90 was a dramatic step up from both
        meth.invoke(client, new Integer(90))
      } catch {
        case _: Exception => ()
      }

      new Network[F](server, client, clientChannelClazz, serverChannelClazz)
    }
  }

  private[this] def uring() =
    try {
      if (
        sys.props
          .get("fs2.netty.use.io_uring")
          .map(_.toBoolean)
          .getOrElse(false)
      ) {
        Class.forName("io.netty.incubator.channel.uring.IOUringEventLoop")

        Some(
          (
            Class.forName(
              "io.netty.incubator.channel.uring.IOUringEventLoopGroup"
            ),
            Class.forName(
              "io.netty.incubator.channel.uring.IOUringServerSocketChannel"
            ),
            Class.forName(
              "io.netty.incubator.channel.uring.IOUringSocketChannel"
            )
          )
        )
      } else {
        None
      }
    } catch {
      case _: Throwable => None
    }

  private[this] def epoll() =
    try {
      Class.forName("io.netty.channel.epoll.EpollEventLoop")

      Some(
        (
          Class.forName("io.netty.channel.epoll.EpollEventLoopGroup"),
          Class.forName("io.netty.channel.epoll.EpollServerSocketChannel"),
          Class.forName("io.netty.channel.epoll.EpollSocketChannel")
        )
      )
    } catch {
      case _: Throwable => None
    }

  private[this] def kqueue() =
    try {
      Class.forName("io.netty.channel.kqueue.KQueueEventLoop")

      Some(
        (
          Class.forName("io.netty.channel.kqueue.KQueueEventLoopGroup"),
          Class.forName("io.netty.channel.kqueue.KQueueServerSocketChannel"),
          Class.forName("io.netty.channel.kqueue.KQueueSocketChannel")
        )
      )
    } catch {
      case _: Throwable => None
    }

  private[this] def nio() =
    (
      Class.forName("io.netty.channel.nio.NioEventLoopGroup"),
      Class.forName("io.netty.channel.socket.nio.NioServerSocketChannel"),
      Class.forName("io.netty.channel.socket.nio.NioSocketChannel")
    )
}
