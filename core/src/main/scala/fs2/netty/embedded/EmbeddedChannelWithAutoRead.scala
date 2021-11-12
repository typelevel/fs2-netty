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

package fs2.netty.embedded

import io.netty.buffer.ByteBuf
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.channel.{ChannelFuture, ChannelPromise}
import io.netty.util.ReferenceCountUtil

import java.nio.channels.ClosedChannelException
import java.util

// Based off of https://github.com/netty/netty/pull/9935/files - WARNING: Java code below
// Should be committed back upstream
class EmbeddedChannelWithAutoRead extends EmbeddedChannel {

  /**
    * Used to simulate socket buffers. When autoRead is false, all inbound information will be temporarily stored here.
    */
  private lazy val tempInboundMessages =
    new util.ArrayDeque[util.AbstractMap.SimpleEntry[Any, ChannelPromise]]()

  def areInboundMessagesBuffered: Boolean = !tempInboundMessages.isEmpty

  def writeInboundFixed(msgs: Any*): Boolean = {
    ensureOpen()
    if (msgs.isEmpty)
      return !inboundMessages().isEmpty

    if (!config().isAutoRead) {
      msgs.foreach(msg =>
        tempInboundMessages.add(
          new util.AbstractMap.SimpleEntry[Any, ChannelPromise](msg, null)
        )
      )
      return false
    }

    val p = pipeline
    for (m <- msgs) {
      p.fireChannelRead(m)
    }

    flushInbound()
    !inboundMessages().isEmpty
  }

  override def writeOneInbound(
    msg: Any,
    promise: ChannelPromise
  ): ChannelFuture = {
    val (isChannelOpen, exception) =
      if (isOpen)
        (true, null)
      else (false, new ClosedChannelException)

    if (isChannelOpen) {
      if (!config().isAutoRead) {
        tempInboundMessages.add(
          new util.AbstractMap.SimpleEntry[Any, ChannelPromise](msg, promise)
        )
        return promise
      } else
        pipeline().fireChannelRead(msg)
    }

    if (exception == null)
      promise.setSuccess()
    else
      promise.setFailure(exception)
  }

  override def doClose(): Unit = {
    super.doClose()
    if (!tempInboundMessages.isEmpty) {
      var exception: ClosedChannelException = null;
      while (true) {
        val entry = tempInboundMessages.poll()
        if (entry == null) {
          return
        }
        val value = entry.getKey;
        if (value != null) {
          ReferenceCountUtil.release(value);
        }
        val promise: ChannelPromise = entry.getValue;
        if (promise != null) {
          if (exception == null) {
            exception = new ClosedChannelException();
          }
          promise.tryFailure(exception);
        }
      }
    }
  }

  override def doBeginRead(): Unit = {
    if (!tempInboundMessages.isEmpty) {
      while (true) {
        val pair = tempInboundMessages.poll();
        if (pair == null) {
          return
        }

        val msg = pair.getKey;
        if (msg != null) {
//          println(s"Firing read ${debug(msg)}")
          pipeline().fireChannelRead(msg)
        }

        val promise = pair.getValue
        if (promise != null) {
          try {
            checkException()
            promise.setSuccess()
          } catch {
            case e: Throwable =>
              promise.setFailure(e)
          }
        }
      }

      // fire channelReadComplete.
      val _ = flushInbound()
    }

  }
}
