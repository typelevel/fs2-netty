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

import com.comcast.ip4s.{IpAddress, SocketAddress}
import io.netty.channel.ChannelPipeline

trait Socket[F[_], I, O, E] {

  def localAddress: F[SocketAddress[IpAddress]]
  def remoteAddress: F[SocketAddress[IpAddress]]

  def reads: Stream[F, I]

  def events: Stream[F, E]

  def write(output: O): F[Unit]
  def writes: Pipe[F, O, INothing]

  def mutatePipeline[I2, O2, E2](
    mutator: ChannelPipeline => F[Unit]
  ): F[Socket[F, I2, O2, E2]]
}
