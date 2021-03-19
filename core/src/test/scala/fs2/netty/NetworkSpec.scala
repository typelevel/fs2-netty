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

class NetworkSpec extends CatsResource[IO, Network[IO]] with SpecificationLike {

  val resource = Network[IO]

  "network tcp sockets" should {
    "create a network instance" in {
      Network[IO].use_.as(ok)
    }

    // TODO: this is a tricky test to pass. It passes the same ByteBuf from client to server. This is problematic b/c
    //  SocketHandler releases after reads, so server releases then client. But Netty throws exception b/c it's
    //  already been released. Another issue is comparing ByteBuf results, the equal method fails on ByteBuf's with
    //  refcount == 0 as there's no data to read (Netty throws exception).
    //  The expectation for typical server/client IO, is that Netty releases the ByteBuf's after writing. This test also
    //  passed before, so perhaps a new bug. Old client would have created a BteBuf from bytes, old server SocketHandler
    //  would have copied out the bytes, released ByteBuf, then when writing, it would create a new ByteBuf which client
    //  would read/release in it's SocketHandler. So there should have been 2 ByteBuf's...need to validate that.
    //  Also what to do when there's just one?! Is this just something in tests or localhost? Maybe SocketHandler
    //  shouldn't release???
//    "support a simple echo use-case" in withResource { net =>
////      val data = List[Byte](1, 2, 3, 4, 5, 6, 7).map(byte => {
//      val data = List[String]("G").map(str => {
//        Unpooled.wrappedBuffer(str.getBytes())
//      })
//
//      val rsrc = net.serverResource(None, None, Nil) flatMap {
//        case (isa, incoming) =>
//          val handler = incoming flatMap { socket =>
//            socket.reads.through(socket.writes)
//          }
//
//          for {
//            _ <- handler.compile.drain.background
//
//            results <- net.client(isa, options = Nil) flatMap { socket =>
//              Stream
//                .emits(data)
//                .through(socket.writes)
//                .merge(socket.reads)
//                .take(data.length.toLong)
//                .compile
//                .resource
//                .toList
//            }
//          } yield results
//      }
//
//      rsrc.use(IO.pure) flatMap { results =>
//        IO {
//          results mustEqual data
//        }
//      }
//    }
  }
}
