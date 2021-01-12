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

import org.specs2.mutable.SpecificationLike

import java.net.InetAddress

class NetworkSpec extends CatsResource[IO, Network[IO]] with SpecificationLike {
  sequential

  val resource = Network[IO]

  "network tcp sockets" should {
    "create a network instance" in {
      Network[IO].use_.as(ok)
    }

    "support a simple echo use-case" in withResource { net =>
      val data = List[Byte](1, 2, 3, 4, 5, 6, 7)

      val rsrc = net.serverResource(InetAddress.getLocalHost(), None) flatMap {
        case (isa, incoming) =>
          val handler = incoming flatMap { socket =>
            socket.reads.through(socket.writes)
          }

          for {
            _ <- handler.compile.drain.background

            results <- net.client(isa) flatMap { socket =>
              Stream.emits(data)
                .through(socket.writes)
                .merge(socket.reads)
                .take(data.length.toLong)
                .compile.resource.toList
            }
          } yield results
      }

      rsrc.use(IO.pure(_)) flatMap { results =>
        IO {
          results mustEqual data
        }
      }
    }
  }
}
