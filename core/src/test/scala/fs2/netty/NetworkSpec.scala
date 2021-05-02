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

import cats.effect.IO
import cats.effect.testing.specs2.CatsResource
import io.netty.buffer.Unpooled
import org.specs2.mutable.SpecificationLike

import java.nio.charset.Charset

class NetworkSpec extends CatsResource[IO, Network[IO]] with SpecificationLike {

  val resource = Network[IO]

  "network tcp sockets" should {
    "create a network instance" in {
      Network[IO].use_.as(ok)
    }

    "support a simple echo use-case" in withResource { net =>
      val data = (1 to 2) // TODO: this breaks with 3; client writes the last element, but server never reads.
        .map(_.toString)
        .toList
        .map(str => {
          (Unpooled.wrappedBuffer(str.getBytes()), str)
        })

      val rsrc = net.serverResource(None, None, Nil) flatMap {
        case (isa, incoming) =>
          val handler = incoming flatMap { socket =>
            socket.reads
              // Without retain, the client seemingly gets the exact same ByteBuf that server processes. This results
              // in exceptions as both client and server release ByteBuf. The root cause is unclear.
              .evalTap(bb => IO(bb.retain()))
              .through(socket.writes)
          }

          for {
            _ <- handler.compile.drain.background

            results <- net.client(isa, options = Nil) flatMap { cSocket =>
              Stream
                .emits(data)
                .map(_._1)
                .through(cSocket.writes)
                .merge(cSocket.reads)
                .take(data.length.toLong)
                .evalMap(bb => IO(bb.toString(Charset.defaultCharset())))
                .compile
                .resource
                .toList
            }
          } yield results
      }

      rsrc.use(IO.pure) flatMap { results =>
        IO {
          results mustEqual data.map(_._2)
        }
      }
    }
  }
}
