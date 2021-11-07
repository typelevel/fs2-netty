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

import cats.effect.testing.specs2.CatsResource
import cats.effect.{IO, Resource}
import io.netty.buffer.{ByteBuf, Unpooled}
import org.specs2.mutable.SpecificationLike

import java.nio.charset.Charset
import scala.collection.mutable.ListBuffer

class NetworkSpec extends CatsResource[IO, Network[IO]] with SpecificationLike {

  override val resource: Resource[IO, Network[IO]] = Network[IO]

  "network tcp sockets" should {
    "create a network instance" in {
      Network[IO].use_.as(ok)
    }

    "support a simple echo use-case" in withResource { net =>
      val msg = "Echo me"

      val rsrc = net.serverResource(None, None, Nil) flatMap {
        case (ip, incoming) =>
          val handler = incoming flatMap { socket =>
            socket.reads
              .evalTap(bb => IO(bb.retain()))
              .through(socket.writes)
          }

          for {
            _ <- handler.compile.drain.background

            results <- net.client(ip, options = Nil) flatMap { cSocket =>
              Stream
                // Send individual bytes as the simplest use case
                .emits(msg.getBytes)
                .evalMap(byteToByteBuf)
                .through(cSocket.writes)
                .merge(cSocket.reads)
                .flatMap(byteBufToStream)
                .take(msg.length.toLong)
                .map(byteToString)
                .compile
                .resource
                .toList
                .map(_.mkString)
            }
          } yield results
      }

      rsrc.use(IO.pure) flatMap { results =>
        IO {
          results mustEqual msg
        }
      }
    }
  }

  private def byteToByteBuf(byte: Byte): IO[ByteBuf] = IO {
    val arr = new Array[Byte](1)
    arr(0) = byte
    Unpooled.wrappedBuffer(new String(arr).getBytes())
  }

  private def byteBufToStream(bb: ByteBuf): Stream[IO, Byte] = {
    val buffer = new ListBuffer[Byte]
    bb.forEachByte((value: Byte) => {
      val _ = buffer.addOne(value)
      true
    })
    Stream.fromIterator[IO].apply[Byte](buffer.iterator, 1)
  }

  private def byteToString(b: Byte): String = {
    val arr = new Array[Byte](1)
    arr(0) = b
    new String(arr, Charset.defaultCharset())
  }
}
