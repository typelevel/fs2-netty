package fs2.netty.pipeline.prebuilt

import cats.effect.std.Dispatcher
import cats.effect.testing.specs2.CatsResource
import cats.effect.{IO, Resource}
import cats.syntax.all._
import fs2.Chunk
import fs2.netty.embedded.Fs2NettyEmbeddedChannel
import fs2.netty.embedded.Fs2NettyEmbeddedChannel.CommonEncoders._
import io.netty.buffer.ByteBuf
import org.specs2.mutable.SpecificationLike

class BytePipelineSpec
    extends CatsResource[IO, Dispatcher[IO]]
    with SpecificationLike {

  override val resource: Resource[IO, Dispatcher[IO]] = Dispatcher[IO]

  "can echo back what is written" in withResource { dispatcher =>
    for {
      pipeline <- BytePipeline(dispatcher)
      x <- Fs2NettyEmbeddedChannel[IO, Chunk[Byte], Byte](pipeline)
      (channel, socket) = x

      _ <- channel.writeAllInboundThenFlushThenRunAllPendingTasks("hello world")
      _ <- socket.reads
        .take(5)
        .chunks
        .through(socket.writes)
        .compile
        .drain

      str <- IO(channel.underlying.readOutbound[ByteBuf]())
        .flatTap(bb => IO(bb.readableBytes() shouldEqual 5))
        .tupleRight(new Array[Byte](5))
        .flatMap { case (buf, bytes) => IO(buf.readBytes(bytes)).as(bytes) }
        .map(new String(_))

      _ <- IO(str shouldEqual "hello")
    } yield ok
  }

  "alternative can echo back what is written" in withResource { dispatcher =>
    for {
      pipeline <- AlternativeBytePipeline(dispatcher)
      x <- Fs2NettyEmbeddedChannel[IO, Chunk[Byte], Byte](pipeline)
      (channel, socket) = x

      _ <- channel.writeAllInboundThenFlushThenRunAllPendingTasks("hello world")
      _ <- socket.reads
        .take(5)
        .chunks
        .through(socket.writes)
        .compile
        .drain

      str <- IO(channel.underlying.readOutbound[ByteBuf]())
        .flatTap(bb => IO(bb.readableBytes() shouldEqual 5))
        .tupleRight(new Array[Byte](5))
        .flatMap { case (buf, bytes) => IO(buf.readBytes(bytes)).as(bytes) }
        .map(new String(_))

      _ <- IO(str shouldEqual "hello")
    } yield ok
  }

}
