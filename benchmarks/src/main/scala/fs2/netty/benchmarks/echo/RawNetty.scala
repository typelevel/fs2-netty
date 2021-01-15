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
import io.netty.channel.{ChannelFuture, ChannelHandler, ChannelHandlerContext, ChannelInitializer, ChannelInboundHandlerAdapter, ChannelOption}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.{NioServerSocketChannel, NioSocketChannel}
import io.netty.channel.socket.SocketChannel

import java.net.InetSocketAddress

object RawNetty {
  def main(args: Array[String]): Unit = {
    val host = args(0)
    val port = args(1).toInt

    val parent = new NioEventLoopGroup(1)
    val child = new NioEventLoopGroup(1)

    val bootstrap = new ServerBootstrap
    bootstrap.group(parent, child)
      .option(ChannelOption.AUTO_READ.asInstanceOf[ChannelOption[Any]], false)
      .option(ChannelOption.SO_REUSEADDR.asInstanceOf[ChannelOption[Any]], true)
      .option(ChannelOption.SO_KEEPALIVE.asInstanceOf[ChannelOption[Any]], false)
      .option(ChannelOption.TCP_NODELAY.asInstanceOf[ChannelOption[Any]], false)
      .channel(classOf[NioServerSocketChannel])
      .childHandler(new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel) = {
          ch.config().setAutoRead(false)
          ch.pipeline().addLast(EchoHandler)
          ch.parent().read()
        }
      })

    val cf = bootstrap.bind(new InetSocketAddress(host, port))
    cf.sync()
    cf.channel.read()
    cf.channel().closeFuture().sync()
  }

  @ChannelHandler.Sharable
  object EchoHandler extends ChannelInboundHandlerAdapter {

    override def channelActive(ctx: ChannelHandlerContext) =
      ctx.channel.read()

    override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef) = {
      ctx.channel.writeAndFlush(msg) addListener { (_: ChannelFuture) =>
        ctx.channel.read()
      }
    }
  }
}
