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
package netty.incudator

import io.netty.channel.ChannelPipeline

trait TcpSocket[F[_], I, O, U] {

  def reads: Stream[F, I]

  def write(output: O): F[Unit]

  def writes: Pipe[F, I, INothing]

  def events: Stream[F, U]

//  def close: F[Unit]

  // TODO: mutator should be ChannelPipeline => F[Unit], but getting import collisions between FS2 stream and Sync in HttpServerConnection
  def mutatePipeline[I2, O2, U2](
    mutator: ChannelPipeline => Unit
  ): F[TcpSocket[F, I2, O2, U2]]
}
