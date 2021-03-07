package fs2.netty

import io.netty.buffer.ByteBufAllocator
import io.netty.channel.{Channel, ChannelConfig, ChannelFuture, ChannelId, ChannelMetadata, ChannelPipeline, ChannelProgressivePromise, ChannelPromise, EventLoop}
import io.netty.util.{Attribute, AttributeKey}

import java.net.SocketAddress

class DeadChannel(parent: Channel) extends Channel {
  override def id(): ChannelId = parent.id()

  override def eventLoop(): EventLoop = parent.eventLoop()

  override def parent(): Channel = parent

  override def config(): ChannelConfig = parent.config()

  override def isOpen: Boolean = false

  override def isRegistered: Boolean = false

  override def isActive: Boolean = false

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

  override def hasAttr[T](key: AttributeKey[T]): Boolean = false

  override def bind(localAddress: SocketAddress): ChannelFuture = parent.voidPromise()

  override def connect(remoteAddress: SocketAddress): ChannelFuture = parent.voidPromise()

  override def connect(remoteAddress: SocketAddress, localAddress: SocketAddress): ChannelFuture = parent.voidPromise()

  override def disconnect(): ChannelFuture = parent.voidPromise()

  override def close(): ChannelFuture = parent.voidPromise()

  override def deregister(): ChannelFuture = parent.voidPromise()

  override def bind(localAddress: SocketAddress, promise: ChannelPromise): ChannelFuture = parent.voidPromise()

  override def connect(remoteAddress: SocketAddress, promise: ChannelPromise): ChannelFuture = parent.voidPromise()

  override def connect(remoteAddress: SocketAddress, localAddress: SocketAddress, promise: ChannelPromise): ChannelFuture = parent.voidPromise()

  override def disconnect(promise: ChannelPromise): ChannelFuture = parent.voidPromise()

  override def close(promise: ChannelPromise): ChannelFuture = parent.voidPromise()

  override def deregister(promise: ChannelPromise): ChannelFuture = parent.voidPromise()

  override def write(msg: Any): ChannelFuture = parent.voidPromise()

  override def write(msg: Any, promise: ChannelPromise): ChannelFuture = parent.voidPromise()

  override def writeAndFlush(msg: Any, promise: ChannelPromise): ChannelFuture = parent.voidPromise()

  override def writeAndFlush(msg: Any): ChannelFuture = parent.voidPromise()

  override def newPromise(): ChannelPromise = parent.newPromise

  override def newProgressivePromise(): ChannelProgressivePromise = parent.newProgressivePromise

  override def newSucceededFuture(): ChannelFuture = parent.voidPromise()

  override def newFailedFuture(cause: Throwable): ChannelFuture = parent.voidPromise()

  override def voidPromise(): ChannelPromise = parent.voidPromise
}

