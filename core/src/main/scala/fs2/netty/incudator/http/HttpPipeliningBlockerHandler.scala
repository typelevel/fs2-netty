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

package fs2.netty.incudator.http

import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise}
import io.netty.handler.codec.http.{DefaultFullHttpResponse, FullHttpRequest, FullHttpResponse, HttpResponseStatus, HttpUtil, HttpVersion}
import io.netty.util.ReferenceCountUtil

class HttpPipeliningBlockerHandler extends ChannelDuplexHandler {

  private var clientAttemptingHttpPipelining = false
  private var isHttpRequestInFlight = false

  override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
    msg match {
      case request: FullHttpRequest =>
        if (!isHttpRequestInFlight) {
          isHttpRequestInFlight = true
          super.channelRead(ctx, msg)
        } else {
          /*
          Stop reading since we're going to close channel
           */
          ctx.channel().config().setAutoRead(false) // TODO: remove this now?
          ReferenceCountUtil.release(request)
          clientAttemptingHttpPipelining = true
        }

      case _ =>
        super.channelRead(ctx, msg)
    }

  override def write(
                      ctx: ChannelHandlerContext,
                      msg: Any,
                      promise: ChannelPromise
                    ): Unit = {
    msg match {
      case _: FullHttpResponse =>
        super.write(ctx, msg, promise)
        isHttpRequestInFlight = false
        if (clientAttemptingHttpPipelining) {
          // TODO: at some point, this can be made more robust to check if 1st response was sent.
          //  Perhaps channel is closed. In which case, don't need to send.
          val response = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.TOO_MANY_REQUESTS
          )
          HttpUtil.setKeepAlive(response, false)
          HttpUtil.setContentLength(response, 0)
          ctx.writeAndFlush(response)
        }

      case _ =>
        super.write(ctx, msg, promise)
    }
  }
}
