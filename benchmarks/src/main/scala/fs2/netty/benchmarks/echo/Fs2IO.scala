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
package benchmarks.echo

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._

import com.comcast.ip4s.{Host, Port}

import fs2.io.net.Network

object Fs2IO extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val host = args(0)
    val port = args(1).toInt

    val handlers = Network[IO].server(Host(host), Port(port)) map { client =>
      client.reads(8096).through(client.writes).attempt.void
    }

    handlers.parJoinUnbounded.compile.drain.as(ExitCode.Success)
  }
}
