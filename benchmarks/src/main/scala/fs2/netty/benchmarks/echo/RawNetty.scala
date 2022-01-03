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

package fs2.netty.benchmarks.echo

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.epoll.{Epoll, EpollEventLoopGroup, EpollServerSocketChannel}
import io.netty.channel.{ChannelFuture, ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelInitializer, ChannelOption}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.SocketChannel

import java.net.InetSocketAddress

object RawNetty {
  def main(args: Array[String]): Unit = {
    val host = args(0)
    val port = args(1).toInt

    val (parent, child, channelClass) = if (Epoll.isAvailable) {
      println("Using epoll")
      (new EpollEventLoopGroup(1),
        new EpollEventLoopGroup(Runtime.getRuntime.availableProcessors()),
        classOf[EpollServerSocketChannel]
      )
    } else {
      println("Using NIO")
      (new NioEventLoopGroup(1),
        new NioEventLoopGroup(Runtime.getRuntime.availableProcessors()),
        classOf[NioServerSocketChannel])
    }

    val bootstrap = new ServerBootstrap
    bootstrap.group(parent, child)
      .option(ChannelOption.AUTO_READ.asInstanceOf[ChannelOption[Any]], false)
      .channel(channelClass)
      .childHandler(new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel) = {
          ch.config().setAutoRead(false)
          ch.pipeline().addLast(new EchoHandler)    // allocating is fair
          ch.parent().read()
          ()
        }
      })

    val cf = bootstrap.bind(new InetSocketAddress(host, port))
    cf.sync()
    cf.channel.read()
    cf.channel().closeFuture().sync()
    ()
  }

  final class EchoHandler extends ChannelInboundHandlerAdapter {

    override def channelActive(ctx: ChannelHandlerContext) = {
      ctx.channel.read()
      ()
    }

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) = {
      ctx.channel.writeAndFlush(msg) addListener { (_: ChannelFuture) =>
        ctx.channel.read()

        ()
      }

      ()
    }

    override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable) = ()
  }
}
