package fs2
package netty.incudator

import cats.effect.Sync
import cats.effect.kernel.Async
import cats.effect.std.Queue
import cats.syntax.all._
import fs2.netty.fromNettyFuture
import io.netty.channel.socket.SocketChannel
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandlerAdapter, ChannelPipeline}
import io.netty.util.ReferenceCountUtil

// Netty handler that acts as a bridge from Netty to FS2
private class TcpSocketHandler[F[_]: Async, I, O, U](
  queue: Queue[F, I],
  channel: SocketChannel
) extends ChannelInboundHandlerAdapter
    with TcpSocket[F, I, O, U] {

  override lazy val reads: Stream[F, I] = ???

  // We should enforce a decoder handler exists in the pipeline for the output object, but probably won't be able
  // to with Netty. Instead might require/recommend that pipelines passed into TCP Network server pass tests
  // with form ByteBuf -> I. Or use a client, O -> ByteBuf, then pass to server, ByteBuf -> I, where I == O, to
  // enforce the correctness property.
  // A hacky way to check is if `ByteToMessageDecoder` or similar Byte based Netty handlers are in the pipeline.

  // Should send from end of pipeline as output object maybe be transformed through the pipeline.
  override def write(output: O): F[Unit] = fromNettyFuture[F](
    Sync[F].delay(channel.pipeline().writeAndFlush(output))
  ).void

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case i: I =>
        queue.offer(
          ReferenceCountUtil.touch(
            i,
            "This is the last point FS2-Netty touches the Reference Counted Object"
          )
        ) // Perhaps once we have good tests, wrapped in resoruce type, and guratentees then we can remove the touch.

      case _ =>
        ReferenceCountUtil.safeRelease(
          msg
        ) // Netty logs if release fails, but perhaps we want to catch error and do custom logging/reporting/handling
    }

  override def writes: Pipe[F, I, INothing] = ???

  override def events: Stream[F, U] = ???

  override def mutatePipeline[I2, O2, U2](
    mutator: ChannelPipeline => Unit
  ): F[TcpSocket[F, I2, O2, U2]] =
    Sync[F]
      .delay(mutator(channel.pipeline()))
      .flatMap(_ => TcpSocketHandler[F, I2, O2, U2](channel))
      .map(
        identity
      ) // TODO: why cannot compiler infer TcpSocketHandler in flatMap?
}

object TcpSocketHandler {

  def apply[F[_]: Async, I, O, U](
    channel: SocketChannel
  ): F[TcpSocketHandler[F, I, O, U]] =
    Queue.unbounded[F, I].map(q => new TcpSocketHandler[F, I, O, U](q, channel))
}
