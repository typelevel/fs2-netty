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

package fs2.netty.pipeline.socket

import io.netty.buffer.ByteBufAllocator
import io.netty.channel._
import io.netty.util.{Attribute, AttributeKey}

import java.net.SocketAddress

/**
  * Void Channel for SocketHandler to prevent writes and further channel effects.
  * Reading state of parent channel is still allowed as it is safe, i.e. no side-effects.
  * @param parent Channel to reference for reading state
  */
class NoopChannel(parent: Channel) extends Channel {

  override def id(): ChannelId = parent.id()

  override def eventLoop(): EventLoop = parent.eventLoop()

  override def parent(): Channel = parent

  override def config(): ChannelConfig = parent.config()

  override def isOpen: Boolean = parent.isOpen

  override def isRegistered: Boolean = parent.isRegistered

  override def isActive: Boolean = parent.isActive

  override def metadata(): ChannelMetadata = parent.metadata

  override def localAddress(): SocketAddress = parent.localAddress

  override def remoteAddress(): SocketAddress = parent.remoteAddress

  override def closeFuture(): ChannelFuture = parent.voidPromise()

  override def isWritable: Boolean = false

  override def bytesBeforeUnwritable(): Long = parent.bytesBeforeUnwritable

  override def bytesBeforeWritable(): Long = parent.bytesBeforeWritable

  override def unsafe(): Channel.Unsafe = parent.unsafe()

  override def pipeline(): ChannelPipeline = parent.pipeline

  override def alloc(): ByteBufAllocator = parent.alloc

  override def read(): Channel = parent.read

  override def flush(): Channel = parent.flush

  override def compareTo(o: Channel): Int = parent.compareTo(o)

  override def attr[T](key: AttributeKey[T]): Attribute[T] = parent.attr(key)

  override def hasAttr[T](key: AttributeKey[T]): Boolean = parent.hasAttr(key)

  override def bind(localAddress: SocketAddress): ChannelFuture =
    parent.voidPromise()

  override def connect(remoteAddress: SocketAddress): ChannelFuture =
    parent.voidPromise()

  override def connect(
    remoteAddress: SocketAddress,
    localAddress: SocketAddress
  ): ChannelFuture = parent.voidPromise()

  override def disconnect(): ChannelFuture = parent.voidPromise()

  override def close(): ChannelFuture = parent.voidPromise()

  override def deregister(): ChannelFuture = parent.voidPromise()

  override def bind(
    localAddress: SocketAddress,
    promise: ChannelPromise
  ): ChannelFuture = parent.voidPromise()

  override def connect(
    remoteAddress: SocketAddress,
    promise: ChannelPromise
  ): ChannelFuture = parent.voidPromise()

  override def connect(
    remoteAddress: SocketAddress,
    localAddress: SocketAddress,
    promise: ChannelPromise
  ): ChannelFuture = parent.voidPromise()

  override def disconnect(promise: ChannelPromise): ChannelFuture =
    parent.voidPromise()

  override def close(promise: ChannelPromise): ChannelFuture =
    parent.voidPromise()

  override def deregister(promise: ChannelPromise): ChannelFuture =
    parent.voidPromise()

  /*
  Below are the key methods we want to overwrite to stop writes
   */

  override def write(msg: Any): ChannelFuture =
    parent.newPromise().setFailure(new NoopChannel.NoopFailure)

  override def write(msg: Any, promise: ChannelPromise): ChannelFuture =
    parent.newPromise().setFailure(new NoopChannel.NoopFailure)

  override def writeAndFlush(msg: Any, promise: ChannelPromise): ChannelFuture =
    parent.newPromise().setFailure(new NoopChannel.NoopFailure)

  override def writeAndFlush(msg: Any): ChannelFuture =
    parent.newPromise().setFailure(new NoopChannel.NoopFailure)

  override def newPromise(): ChannelPromise = parent.newPromise

  override def newProgressivePromise(): ChannelProgressivePromise =
    parent.newProgressivePromise

  override def newSucceededFuture(): ChannelFuture =
    parent.newPromise().setFailure(new NoopChannel.NoopFailure)

  override def newFailedFuture(cause: Throwable): ChannelFuture =
    parent.newPromise().setFailure(new NoopChannel.NoopFailure)

  override def voidPromise(): ChannelPromise = parent.voidPromise
}

object NoopChannel {
  private class NoopFailure extends Throwable("Noop channel")
}
