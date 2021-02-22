package fs2.netty.incudator.http

import fs2.netty.incudator.TcpSocket
import fs2.{INothing, Pipe, Stream}
import io.netty.channel.ChannelPipeline
import io.netty.handler.codec.http.websocketx.WebSocketFrame

class WebSocket[F[_], U](
  underlying: TcpSocket[F, WebSocketFrame, WebSocketFrame, Nothing]
) extends TcpSocket[F, WebSocketFrame, WebSocketFrame, U] {
  override def reads: Stream[F, WebSocketFrame] = underlying.reads

  // TODO: this will be aware of close frames
  override def write(output: WebSocketFrame): F[Unit] =
    underlying.write(output)

  override def writes: Pipe[F, WebSocketFrame, INothing] = underlying.writes

  override def events: Stream[F, Nothing] = underlying.events

  override def mutatePipeline[I2, O2, U2](
    mutator: ChannelPipeline => Unit
  ): F[TcpSocket[F, I2, O2, U2]] =
    underlying.mutatePipeline(mutator)
}
