package fs2.netty.pipeline

import cats.Eval
import cats.effect.std.{Dispatcher, Queue}
import cats.effect.testing.specs2.CatsResource
import cats.effect.{IO, Resource}
import cats.syntax.all._
import fs2.Stream
import fs2.netty.embedded.Fs2NettyEmbeddedChannel
import fs2.netty.embedded.Fs2NettyEmbeddedChannel.CommonEncoders._
import fs2.netty.embedded.Fs2NettyEmbeddedChannel.Encoder
import fs2.netty.pipeline.NettyPipelineSpec._
import fs2.netty.pipeline.socket.Socket
import io.netty.buffer.{ByteBuf, Unpooled}
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import io.netty.channel.{ChannelHandlerContext, ChannelInboundHandler}
import io.netty.handler.codec.MessageToMessageDecoder
import io.netty.handler.codec.bytes.{ByteArrayDecoder, ByteArrayEncoder}
import io.netty.handler.codec.string.StringDecoder
import io.netty.util.ReferenceCountUtil
import org.specs2.mutable.SpecificationLike

import java.nio.channels.ClosedChannelException
import java.util
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class NettyPipelineSpec
    extends CatsResource[IO, Dispatcher[IO]]
    with SpecificationLike {

  override val resource: Resource[IO, Dispatcher[IO]] = Dispatcher[IO]

  "default pipeline, i.e. no extra Channel handlers and reads and writes are on ByteBuf's" should {
    "no activity on Netty channel should correspond to no activity on socket and vice-versa" in withResource {
      implicit dispatcher =>
        for {
          // Given a socket and embedded channel from the default Netty Pipeline
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x

          // Then configs should be setup for backpressure
          _ <- IO(channel.underlying.config().isAutoRead should beFalse)
          _ <- IO(channel.underlying.config().isAutoClose should beTrue)
          _ <- IO(
            channel.underlying
              .config()
              .getWriteBufferLowWaterMark shouldEqual 32 * 1024
          )
          _ <- IO(
            channel.underlying
              .config()
              .getWriteBufferHighWaterMark shouldEqual 64 * 1024
          )

          // When flushing inbound events, i.e. calling read complete, on an empty channel
          _ <- channel.flushInbound()

          // Then there are no reads on socket reads stream
          // TODO: what's the canonical way to check for empty stream?
          reads <- socket.reads
            .interruptAfter(Duration.Zero)
            .compile
            .toList
          _ <- IO(reads should beEmpty)

          // When trigger Netty to run events when there aren't any to run
          nextTaskTime <- channel.runScheduledPendingTasks
          _ <- IO(nextTaskTime shouldEqual Fs2NettyEmbeddedChannel.NoTasksToRun)

          // Then there should be no events on the socket events stream
          events: List[AnyRef] <- socket.events
            .interruptAfter(Duration.Zero)
            .compile
            .toList
          _ <- IO(events should beEmpty)

          // When there's no activity on Netty channel, but channel is still active
          _ <- IO(channel.isOpen)

          // Then there should not be any exceptions
          isOpen <- socket.isOpen
          _ <- IO(isOpen shouldEqual true)
          isClosed <- socket.isClosed
          _ <- IO(isClosed shouldEqual false)

          // When there's no activity on socket writes
          _ <- channel.flushOutbound()

          // Then there's no message on Netty channel outbound queue
          writes <- channel.outboundMessages
          _ <- IO(writes.isEmpty shouldEqual true)

          // And finally, no exceptions on the Netty channel
          _ <- IO(channel.underlying.checkException())
        } yield ok
    }

    "reading on socket without backpressure results in from Netty reading onto its channel" in withResource {
      implicit dispatcher =>
        for {
          // Given a socket and embedded channel from the default Netty Pipeline
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x

          // And list of ByteBuf's
          byteBufs <- IO(
            List(
              Unpooled.wrappedBuffer("hello".getBytes),
              Unpooled.buffer(1, 1).writeByte(' '),
              Unpooled.copiedBuffer("world".getBytes)
            )
          )

          // And a socket that doesn't backpressure reads, i.e. always accepts elements from stream
          queue <- Queue.unbounded[IO, String]
          _ <- socket.reads
            .flatMap(byteBufToByteStream)
            .map(byteToString)
            .evalMap(queue.offer)
            .compile
            .drain
            .background
            .use { _ =>
              for {
                // When writing each ByteBuf individually to the channel
                _ <- channel
                  .writeAllInboundThenFlushThenRunAllPendingTasks(byteBufs: _*)

                // Then messages should be consumed from Netty
                _ <- IO.sleep(200.millis)
                _ <- IO(
                  channel.underlying.areInboundMessagesBuffered shouldEqual false
                )

                // And reads on socket yield the original message sent on channel
                str <- (0 until "hello world".length).toList
                  .traverseFilter(_ => queue.tryTake)
                  .map(_.mkString)
                _ <- IO(str shouldEqual "hello world")

                // And ByteBuf's should be released
                _ <- IO(byteBufs.map(_.refCnt()) shouldEqual List.fill(3)(0))
              } yield ()
            }
        } yield ok
    }

    "backpressure on socket reads results in Netty NOT reading onto its channel" in withResource {
      implicit dispatcher =>
        for {
          // Given a socket and embedded channel from the default Netty Pipeline
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x

          // And list of ByteBuf's
          byteBufs <- IO(
            List(
              Unpooled.wrappedBuffer("hello".getBytes),
              Unpooled.buffer(1, 1).writeByte(' '),
              Unpooled.copiedBuffer("world".getBytes)
            )
          )

          // And a socket with backpressure, i.e. socket reads aren't being consumed

          // When writing each ByteBuf to the channel
          areMsgsAdded <- channel
            .writeAllInboundThenFlushThenRunAllPendingTasks(byteBufs: _*)

          // Then messages are NOT added onto the Netty channel
          _ <- IO.sleep(
            200.millis
          ) // give fs2-Netty chance to read like in non-backpressure test
          _ <- IO(areMsgsAdded should beFalse)
          _ <- IO(
            channel.underlying.areInboundMessagesBuffered shouldEqual true
          )

          // And reads on socket yield the original message sent on channel
          str <- socket.reads
            .flatMap(byteBufToByteStream)
            .take(11)
            .map(byteToString)
            .compile
            .toList
            .map(_.mkString)
          _ <- IO(str shouldEqual "hello world")

          // And ByteBuf's should be released
          _ <- IO(byteBufs.map(_.refCnt()) shouldEqual List.fill(3)(0))
        } yield ok
    }

    "writing onto fs2-netty socket appear on Netty's channel" in withResource {
      implicit dispatcher =>
        for {
          // Given a socket and embedded channel from the default Netty Pipeline
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x

          // And list of ByteBuf's
          byteBufs <- IO(
            List(
              Unpooled.wrappedBuffer("hello".getBytes),
              Unpooled.buffer(1, 1).writeByte(' '),
              Unpooled.copiedBuffer("world".getBytes)
            )
          )

          // When writing each ByteBuf to the socket
          _ <- byteBufs.traverse(socket.write)

          // Then Netty channel has messages in its outbound queue
          str <- Stream
            .fromIterator[IO]((0 until 3).iterator, chunkSize = 100)
            .evalMap(_ => IO(channel.underlying.readOutbound[ByteBuf]()))
            .flatMap(byteBufToByteStream)
            .take(11)
            .map(byteToString)
            .compile
            .toList
            .map(_.mkString)
          _ <- IO(str shouldEqual "hello world")

          // And ByteBuf's are not released. Embedded channel doesn't release, but real channel should.
          _ <- IO(byteBufs.map(_.refCnt()) shouldEqual List.fill(3)(1))
          _ <- IO.unit.guarantee(
            IO(byteBufs.foreach(ReferenceCountUtil.release))
          )
        } yield ok
    }

    "closed connection in Netty appears as closed streams in fs2-netty" in withResource {
      implicit dispatcher =>
        for {
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x

          // Netty sanity check
          _ <- channel.isOpen.flatMap(isOpen => IO(isOpen should beTrue))
          _ <- socket.isOpen.flatMap(isOpen => IO(isOpen should beTrue))

          _ <- channel.close()

          // Netty sanity check
          _ <- channel.isClosed.flatMap(isClosed => IO(isClosed should beTrue))
          _ <- socket.isOpen.flatMap(isOpen => IO(isOpen should beFalse))
          _ <- socket.isClosed.flatMap(isClosed => IO(isClosed should beTrue))
        } yield ok
    }

    "closing connection in fs2-netty closes underlying Netty channel" in withResource {
      implicit dispatcher =>
        for {
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x

          _ <- socket.close()

          _ <- channel.isClosed.flatMap(isClosed => IO(isClosed should beTrue))
          _ <- socket.isOpen.flatMap(isOpen => IO(isOpen should beFalse))
          _ <- socket.isClosed.flatMap(isClosed => IO(isClosed should beTrue))
        } yield ok
    }

    "writing onto a closed socket is a no-op and throws an exception" in withResource {
      implicit dispatcher =>
        for {
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x
          _ <- channel.close()

          byteBuf <- IO(Unpooled.wrappedBuffer("hi".getBytes))
          caughtClosedChannelException <- socket
            .write(byteBuf)
            .as(false)
            .handleErrorWith {
              case _: ClosedChannelException => true.pure[IO]
              case _ => false.pure[IO]
            }

          _ <- IO(caughtClosedChannelException shouldEqual true)

          _ <- channel.outboundMessages.flatMap(out =>
            IO(out.isEmpty shouldEqual true)
          )
        } yield ok
    }

    "exceptions in Netty pipeline raises an exception on the reads stream" in withResource {
      implicit dispatcher =>
        for {
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x

          _ <- IO(
            channel.underlying
              .pipeline()
              .fireExceptionCaught(new Throwable("unit test error"))
          )

          errMsg <- socket.reads
            .map(_ => "")
            .handleErrorWith(t => Stream.emit(t.getMessage))
            .compile
            .last
          _ <- IO(errMsg shouldEqual "unit test error".some)

          _ <- channel.isOpen.flatMap(isOpen => IO(isOpen shouldEqual true))
          _ <- socket.isOpen.flatMap(isOpen => IO(isOpen shouldEqual true))
        } yield ok
    }

    "pipeline events appear in fs2-netty as events stream" in withResource {
      implicit dispatcher =>
        for {
          x <- NettyEmbeddedChannelWithByteBufPipeline
          (channel, socket) = x

          _ <- IO(
            channel.underlying
              .pipeline()
              .fireUserEventTriggered(ChannelInputShutdownReadComplete.INSTANCE)
          )

          event <- socket.events.take(1).compile.last
        } yield event should_=== Some(ChannelInputShutdownReadComplete.INSTANCE)
    }

    "mutations" should {
      "no-op mutation creates a Socket with same behavior as original, while original Socket is unregistered from pipeline and channel" in withResource {
        dispatcher =>
          for {
            // Given a channel and socket for the default pipeline
            pipeline <- NettyPipeline[IO](dispatcher)
            x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf](
              pipeline
            )
            (channel, socket) = x

            // Then socket is attached to a pipeline
            _ <- socket.isDetached.map(_ should beFalse)

            // When performing a no-op socket pipeline mutation
            newSocket <- socket.mutatePipeline[ByteBuf, ByteBuf](_ => IO.unit)

            // Then new socket should be able to receive and write ByteBuf's
            encoder = implicitly[Encoder[Byte]]
            byteBufs = "hello world".getBytes().map(encoder.encode).toList
            _ <- channel
              .writeAllInboundThenFlushThenRunAllPendingTasks(byteBufs: _*)
            _ <- newSocket.reads
              // fs2-netty automatically releases
              .evalMap(bb => IO(bb.retain()))
              .take(11)
              .through(newSocket.writes)
              .compile
              .drain
            str <- (0 until 11).toList
              .traverse { _ =>
                IO(channel.underlying.readOutbound[ByteBuf]())
                  .flatMap(bb => IO(bb.readByte()))
              }
              .map(_.toArray)
              .map(new String(_))
            _ <- IO(str shouldEqual "hello world")

            // And new socket is attached to a pipeline
            _ <- newSocket.isDetached.map(_ should beFalse)

            // And old socket is no longer attached to a pipeline
            _ <- socket.isDetached.map(_ should beTrue)

            // And old socket should not receive any of the ByteBuf's
            oldSocketReads <- socket.reads
              .interruptAfter(1.second)
              .compile
              .toList
            _ <- IO(oldSocketReads should beEmpty)

            // Nor should old socket be able to write.
            oldSocketWrite <- socket.write(Unpooled.EMPTY_BUFFER).attempt
            _ <- IO(oldSocketWrite should beLeft[Throwable].like { case t =>
              t.getMessage should_=== ("Noop channel")
            })
            _ <- IO(channel.underlying.outboundMessages().isEmpty should beTrue)
          } yield ok
      }

      // varies I/O types and along with adding a handler that changes byteBufs to constant strings, affects reads stream and socket writes
      "vary the Socket types" in withResource { dispatcher =>
        for {
          // Given a channel and socket for the default pipeline
          pipeline <- NettyPipeline[IO](dispatcher)
          x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf](
            pipeline
          )
          (channel, socket) = x

          pipelineDecoder = new Socket.Decoder[Array[Byte]] {
            override def decode(x: AnyRef): Either[String, Array[Byte]] =
              x match {
                case array: Array[Byte] => array.asRight[String]
                case _ =>
                  "whoops, pipeline is misconfigured".asLeft[Array[Byte]]
              }
          }
          byteSocket <- socket
            .mutatePipeline[Array[Byte], Array[Byte]] { pipeline =>
              for {
                _ <- IO(pipeline.addLast(new ByteArrayDecoder))
                _ <- IO(pipeline.addLast(new ByteArrayEncoder))
              } yield ()
            }(pipelineDecoder)

          byteBuf = implicitly[Encoder[Array[Byte]]]
            .encode("hello world".getBytes())
          _ <- channel
            .writeAllInboundThenFlushThenRunAllPendingTasks(byteBuf)
          _ <- byteSocket.reads
            .take(1)
            .through(byteSocket.writes)
            .compile
            .drain

          str <- IO(channel.underlying.readOutbound[ByteBuf]())
            .flatTap(bb => IO(bb.readableBytes() shouldEqual 11))
            .tupleRight(new Array[Byte](11))
            .flatMap { case (buf, bytes) => IO(buf.readBytes(bytes)).as(bytes) }
            .map(new String(_))
          _ <- IO(str shouldEqual "hello world")
        } yield ok
      }

      // pipeline mutation error

      // socket decode error

      // test reads, writes, events, and exceptions in combination to ensure order of events makes sense
    }

    // test pipeline with ByteArrayEncoder/Decoder passed into pipeline, not mutation
  }

  "custom pipelines" should {
    implicit val stringSocketDecoder: Socket.Decoder[String] = {
      case str: String => str.asRight[String]
      case _ => "pipeline misconfigured".asLeft[String]
    }

    "custom handlers can change the types of reads and writes " in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO, String, String](
            dispatcher,
            handlers = List(Eval.now(new StringDecoder))
          )
          x <- Fs2NettyEmbeddedChannel[IO, String, String](pipeline)
          (channel, socket) = x

          _ <- channel.writeAllInboundThenFlushThenRunAllPendingTasks(
            "hello",
            " ",
            "world"
          )

          strings <- socket.reads.take(3).compile.toList

          _ <- IO(strings.mkString("") should_=== "hello world")

          _ <- socket.write("output message")

          msg <- IO(channel.underlying.readOutbound[String]())
          _ <- IO(msg should_=== "output message")
        } yield ok
    }

    // tests should enforce that ByteBuf is read off embedded channel ^^

    "non sharable handlers must be always evaluated per channel" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO, String, String](
            dispatcher,
            handlers =
              List(Eval.always(new StatefulMessageToReadCountChannelHandler))
          )
          x <- Fs2NettyEmbeddedChannel[IO, String, String](pipeline)
          (channelOne, socketOne) = x
          y <- Fs2NettyEmbeddedChannel[IO, String, String](pipeline)
          (channelTwo, socketTwo) = y

          inputs = List("a", "b", "c")

          // for same input to each channel we expect the same output, i.e. same scan of counts
          _ <- channelOne.writeAllInboundThenFlushThenRunAllPendingTasks(
            inputs: _*
          )
          countsOne <- socketOne.reads.take(3).map(_.toInt).compile.toList
          _ <- IO(countsOne should_=== List(1, 2, 3))

          _ <- channelTwo.writeAllInboundThenFlushThenRunAllPendingTasks(
            inputs: _*
          )
          countsTwo <- socketTwo.reads.take(3).map(_.toInt).compile.toList
          _ <- IO(countsTwo should_=== List(1, 2, 3))
        } yield ok
    }

    "sharable handlers are memoized per channel regardless of the eval policy" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO, String, String](
            dispatcher,
            handlers = List(
              Eval.always(
                new SharableStatefulByteBufToReadCountChannelHandler
              ),
              Eval.now(
                new SharableStatefulStringToReadCountChannelHandler
              ),
              Eval.later(
                new SharableStatefulStringToReadCountChannelHandler
              )
            )
          )
          x <- Fs2NettyEmbeddedChannel[IO, String, String](pipeline)
          (channelOne, socketOne) = x
          y <- Fs2NettyEmbeddedChannel[IO, String, String](pipeline)
          (channelTwo, socketTwo) = y

          inputs = List("a", "b", "c")

          _ <- channelOne.writeAllInboundThenFlushThenRunAllPendingTasks(
            inputs: _*
          )
          countsOne <- socketOne.reads.take(3).map(_.toInt).compile.toList
          _ <- IO(countsOne should_=== List(1, 2, 3))

          _ <- channelTwo.writeAllInboundThenFlushThenRunAllPendingTasks(
            inputs: _*
          )
          countsTwo <- socketTwo.reads.take(3).map(_.toInt).compile.toList
          _ <- IO(countsTwo should_=== List(4, 5, 6))
        } yield ok
    }
  }

  private def byteToString(byte: Byte): String = {
    val bytes = new Array[Byte](1)
    bytes(0) = byte
    new String(bytes)
  }
}

object NettyPipelineSpec {

  private def NettyEmbeddedChannelWithByteBufPipeline(implicit
    dispatcher: Dispatcher[IO]
  ) =
    for {
      pipeline <- NettyPipeline[IO](dispatcher)
      x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf](pipeline)
    } yield x

  private def byteBufToByteStream(bb: ByteBuf): Stream[IO, Byte] = {
    val buffer = new ListBuffer[Byte]
    bb.forEachByte((value: Byte) => {
      val _ = buffer.addOne(value)
      true
    })
    Stream.fromIterator[IO](buffer.iterator, 1)
  }

  /**
    * Does not use MessageToMessageDecoder, SimpleChannelInboundHandler, or anything that extends ChannelHandlerAdapter.
    * Netty tacks if a ChannelHandlerAdapter annotated with @Sharable is added. Netty will throw an exception if such a
    * handler would be reused, e.g.
    * io.netty.channel.ChannelInitializer exceptionCaught
    * WARNING: Failed to initialize a channel. Closing: [id: 0xembedded, L:embedded - R:embedded]
    * io.netty.channel.ChannelPipelineException: fs2.netty.NettyPipelineSpec$StatefulMessageToReadCountChannelHandler is not a @Sharable handler, so can't be added or removed multiple tim
    */
  private class StatefulMessageToReadCountChannelHandler
      extends ChannelInboundHandler {
    private var readCounter = 0

    override def channelRegistered(ctx: ChannelHandlerContext): Unit =
      ctx.fireChannelRegistered()

    override def channelUnregistered(ctx: ChannelHandlerContext): Unit =
      ctx.fireChannelUnregistered()

    override def channelActive(ctx: ChannelHandlerContext): Unit =
      ctx.fireChannelActive()

    override def channelInactive(ctx: ChannelHandlerContext): Unit =
      ctx.fireChannelInactive()

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit = {
      ReferenceCountUtil.safeRelease(msg)
      readCounter += 1
      ctx.fireChannelRead(readCounter.toString)
    }

    override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
      ctx.fireChannelReadComplete()

    override def userEventTriggered(
      ctx: ChannelHandlerContext,
      evt: Any
    ): Unit =
      ctx.fireUserEventTriggered()

    override def channelWritabilityChanged(ctx: ChannelHandlerContext): Unit =
      ctx.fireChannelWritabilityChanged()

    override def exceptionCaught(
      ctx: ChannelHandlerContext,
      cause: Throwable
    ): Unit =
      ctx.fireExceptionCaught(cause)

    override def handlerAdded(ctx: ChannelHandlerContext): Unit = ()

    override def handlerRemoved(ctx: ChannelHandlerContext): Unit = ()
  }

  @Sharable
  private class SharableStatefulStringToReadCountChannelHandler
      extends MessageToMessageDecoder[String] {
    private var readCounter = 0

    override def decode(
      ctx: ChannelHandlerContext,
      msg: String,
      out: util.List[AnyRef]
    ): Unit = {
      readCounter += 1
      out.add(readCounter.toString)
    }
  }

  @Sharable
  private class SharableStatefulByteBufToReadCountChannelHandler
      extends MessageToMessageDecoder[ByteBuf] {
    private var readCounter = 0

    override def decode(
      ctx: ChannelHandlerContext,
      msg: ByteBuf,
      out: util.List[AnyRef]
    ): Unit = {
      readCounter += 1
      out.add(readCounter.toString)
    }
  }

}
