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

package fs2.netty

import cats.effect.Async
import cats.syntax.all._

import io.netty.channel.ChannelFuture

private class PartiallyAppliedPlatform[F[_]] { this: PartiallyApplied[F] =>
  // this only needs to exist because the Scala 2 compiler is really bad at subtyping
  def apply(cf: F[ChannelFuture])(implicit F: Async[F], D: DummyImplicit): F[Void] =
    apply[Void](cf.widen)
}
