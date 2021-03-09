package fs2.netty

import io.netty.channel.{Channel, ChannelInitializer}
import io.netty.channel.socket.SocketChannel

trait NettyChannelInitializer[F[_], I, O, E] {

  def toSocketChannelInitializer(
    cb: Socket[F, I, O, E] => F[Unit]
  ): F[ChannelInitializer[SocketChannel]] =
    toChannelInitializer[SocketChannel](cb)

  def toChannelInitializer[C <: Channel](
    cb: Socket[F, I, O, E] => F[Unit]
  ): F[ChannelInitializer[C]]
}
