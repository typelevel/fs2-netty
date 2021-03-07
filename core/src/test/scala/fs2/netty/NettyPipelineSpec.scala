package fs2
package netty

import cats.effect.std.Dispatcher
import cats.effect.testing.specs2.CatsResource
import cats.effect.{IO, Resource}
import cats.syntax.all._
import fs2.netty.embedded.Fs2NettyEmbeddedChannel
import fs2.netty.embedded.Fs2NettyEmbeddedChannel.CommonEncoders._
import fs2.netty.embedded.Fs2NettyEmbeddedChannel.Encoder
import io.netty.buffer.{ByteBuf, Unpooled}
import org.specs2.mutable.SpecificationLike

import scala.concurrent.duration._

class NettyPipelineSpec
    extends CatsResource[IO, Dispatcher[IO]]
    with SpecificationLike {

  // TODO: where does 10s timeout come from?
  override val resource: Resource[IO, Dispatcher[IO]] = Dispatcher[IO]

  "default pipeline, i.e. no extra Channel handlers" should {
    "zero reads in Netty corresponds to an empty fs2-netty ByteBuf reads stream" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO](dispatcher)
          socket <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](
            pipeline
          ).map(_._2)

          reads <- socket.reads
            .interruptAfter(1.second)
            .compile
            .toList // TODO: what's the proper way to check for empty stream?
        } yield reads should beEmpty
    }

    "zero events in Netty pipeline corresponds to an empty fs2-netty events stream" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO](dispatcher)
          socket <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](
            pipeline
          ).map(_._2)

          events: List[Nothing] <- socket.events
            .interruptAfter(1.second)
            .compile
            .toList
        } yield events should beEmpty
    }

    "reads from Netty appear in fs2-netty as reads stream as ByteBuf objects" in withResource {
      dispatcher =>
        for {
          // Given a socket and embedded channel from the default Netty Pipeline
          pipeline <- NettyPipeline[IO](dispatcher)
          x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](pipeline)
          (channel, socket) = x

          // Then configs should be setup, like autoread should be false...maybe move to top test?
          _ <- IO(channel.underlying.config().isAutoRead should beFalse)
          _ <- IO(channel.underlying.config().isAutoClose should beTrue)

          // And list of single byte ByteBuf's
          encoder = implicitly[Encoder[Byte]]
          byteBufs = "hello world".getBytes().map(encoder.encode)

          // When writing each ByteBuf individually to the channel
          areMsgsAdded <- channel
            .writeAllInboundThenFlushThenRunAllPendingTasks(byteBufs: _*)

          // Then messages aren't added to the inbound buffer because autoread should be off
          _ <- IO(areMsgsAdded should beFalse)

          // And reads on socket yield the original message sent on channel
          str <- socket.reads
            .map(_.readByte())
            .take(11)
            .foldMap(byteToString)
            .compile
            .last
          _ <- IO(str shouldEqual "hello world".some)

          // And ByteBuf's should be released
          _ <- IO(byteBufs.map(_.refCnt()) shouldEqual Array.fill(11)(0))
        } yield ok
    }

    "writing ByteBuf's onto fs2-netty socket appear on Netty's channel" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO](dispatcher)
          x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](pipeline)
          (channel, socket) = x

          encoder = implicitly[Encoder[Byte]]
          byteBufs = "hello world".getBytes().map(encoder.encode).toList
          // TODO: make resource?
//          _ <- IO.unit.guarantee(IO(byteBufs.foreach(ReferenceCountUtil.release)))

          _ <- byteBufs.traverse(socket.write)

          str <- (0 until 11).toList
            .traverse { _ =>
              IO(channel.underlying.readOutbound[ByteBuf]())
                .flatMap(bb => IO(bb.readByte()))
            }
            .map(_.toArray)
            .map(new String(_))

          _ <- IO(str shouldEqual "hello world")
        } yield ok
    }

    "piping any reads to writes just echos back ByteBuf's written onto Netty's channel" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO](dispatcher)
          x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](pipeline)
          (channel, socket) = x

          encoder = implicitly[Encoder[Byte]]
          byteBufs = "hello world".getBytes().map(encoder.encode).toList

          _ <- channel
            .writeAllInboundThenFlushThenRunAllPendingTasks(byteBufs: _*)
          _ <- socket.reads
          // fs2-netty automatically releases
            .evalMap(bb => IO(bb.retain()))
            .take(11)
            .through(socket.writes)
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
        } yield ok
    }

    "closed connection in Netty appears as closed streams in fs2-netty" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO](dispatcher)
          x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](pipeline)
          (channel, socket) = x

          // Netty sanity check
          _ <- channel.isOpen.flatMap(isOpen => IO(isOpen should beTrue))
          _ <- socket.isOpen.flatMap(isOpen => IO(isOpen should beTrue))

          // TODO: wrapper methods for underlying
          _ <- channel.close()

          // Netty sanity check, maybe move these to their own test file for Embedded Channel
          _ <- channel.isClosed.flatMap(isClosed => IO(isClosed should beTrue))
          _ <- socket.isOpen.flatMap(isOpen => IO(isOpen should beFalse))
          _ <- socket.isClosed.flatMap(isClosed => IO(isClosed should beTrue))
        } yield ok
    }

    "closing connection in fs2-netty closes underlying Netty channel" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO](dispatcher)
          x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](pipeline)
          (channel, socket) = x

          _ <- socket.close()

          _ <- channel.isClosed.flatMap(isClosed => IO(isClosed should beTrue))
          _ <- socket.isOpen.flatMap(isOpen => IO(isOpen should beFalse))
          _ <- socket.isClosed.flatMap(isClosed => IO(isClosed should beTrue))
        } yield ok
    }

    "exceptions in Netty pipeline raises an exception on the reads stream" in withResource {
      dispatcher =>
        for {
          pipeline <- NettyPipeline[IO](dispatcher)
          x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](pipeline)
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
        } yield errMsg shouldEqual "unit test error".some
    }

    "mutations" should {
      "no-op mutation creates a Socket with same behavior as original, while original Socket is unregistered from pipeline and channel" in withResource {
        dispatcher =>
          for {
            // Given a channel and socket for the default pipeline
            pipeline <- NettyPipeline[IO](dispatcher)
            x <- Fs2NettyEmbeddedChannel[IO, ByteBuf, ByteBuf, Nothing](
              pipeline
            )
            (channel, socket) = x

            // Then socket is attached to a pipeline
            _ <- socket.isDetached.map(_ should beFalse)

            // When performing a no-op socket pipeline mutation
            newSocket <- socket.mutatePipeline[ByteBuf, ByteBuf, Nothing](_ =>
              IO.unit)

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
            _ <- IO(oldSocketWrite should beLeft[Throwable].like {
              case t =>
                t.getMessage should_=== ("Noop channel")
            })
            _ <- IO(channel.underlying.outboundMessages().isEmpty should beTrue)
          } yield ok
      }

      // varies I/O types and along with adding a handler that changes byteBufs to constant strings, affects reads stream and socket writes
    }

    // test reads, writes, events, and exceptions in combination to ensure order of events makes sense
  }

//  "byte to byte pipeline" should {}

//  "custom pipeline" should {
// repeat tests from above
//    "pipelines that decode ByteBuf into I then fires channelRead, shows up in read stream"
//    "pipelines that DO NOT decode ByteBuf into I but fire channelRead, DO NOT show up in read stream"
//    "pipelines that encode O into ByteBuf (and go on to write to Netty), will send ByteBuf"
//    "pipelines that encode O into ByteBuf (and DO NOT go on to write to Netty) (idk what kind of case this is), will NOT send ByteBuf"
//    "pipelines that DO NOT encode O into ByteBuf, will NOT send ByteBuf"
//    "pipelines that emit user triggered events of type E will show up in events stream"
//    "pipelines that emit user triggered events of NOT type E will raise error in events stream"
//    "connections that close, will shut off reads stream, writes will fail/cancel"
//    "handlerAdded test???"
//    "socket closes???"
//    "pipelines that emit exceptions will raise error on reads stream"
//    "pipelines that remove SocketHandler, will raise error on reads and events streams. Except in case of mutation"

//    "test I1->Bytes, then Bytes->I2 (pipeline), and I1 == I2...nvm ByteBuf can be thrown away, decode may be" +
//    "non-deterministic by design. Too many assumptions. Maybe can provide tests for specific cases that require correctness property, but not all" in {
//      ok
//    }
//  }

//  "there can be a pipeline that only sends, I = Nothing" in { ok }
//  "there can be a pipeline that only receives, O = Nothing" in { ok }

//  "chunking..." in { ok }

  private def byteToString(byte: Byte): String = {
    val bytes = new Array[Byte](1)
    bytes(0) = byte
    new String(bytes)
  }
}
