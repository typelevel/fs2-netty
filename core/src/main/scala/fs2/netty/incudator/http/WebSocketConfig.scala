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

import fs2.netty.incudator.http.WebSocketConfig.DisableTimeout
import io.netty.handler.codec.http.websocketx.{WebSocketCloseStatus, WebSocketDecoderConfig, WebSocketServerProtocolConfig}


/**
 *
 * @param maxFramePayloadLength - limit on payload length from Text and Binary Frames
 * @param allowExtensions       - WS extensions like those for compression
 * @param subProtocols          - optional subprotocols to negotiate
 * @param utf8FrameValidation   - optionally validate text frames' payloads are utf8
 */
final case class WebSocketConfig(
                                  maxFramePayloadLength: Int,
                                  allowExtensions: Boolean,
                                  subProtocols: List[String],
                                  utf8FrameValidation: Boolean
                                ) {

  def toNetty: WebSocketServerProtocolConfig =
    WebSocketServerProtocolConfig
      .newBuilder()

      // Match all paths, let application filter requests.
      .websocketPath("/")
      .checkStartsWith(true)
      .subprotocols(subProtocolsCsv)

      // Application will handle timeouts for WS Handshake request. Set this far into the future b/c Netty doesn't
      // not allow non-positive values in configs.
      .handshakeTimeoutMillis(200000L) // 200 sec

      // Application will handle all inbound close frames, this flag tells Netty to handle them
      .handleCloseFrames(false)

      // Application will handle all Control Frames
      .dropPongFrames(false)

      // Netty's WebSocketCloseFrameHandler ensures Close Frames are sent on close (if they weren't sent before)
      // and closes always send a Close Frame.
      // It also checks that no new messages are sent after Close Frame is sent, throwing a ClosedChannelException.
      // It would be nice to set it as INTERNAL_SERVER_ERROR since applications should handle closes, but b/c
      // of a weird bug in Netty, this is triggered when UTF8 validation fails, so setting it to INVALID_PAYLOAD_DATA.
      .sendCloseFrame(WebSocketCloseStatus.INVALID_PAYLOAD_DATA)

      // Netty can check that Close Frame has been sent in some time period, but we don't need this option because
      // application should close channel immediately after each close
      .forceCloseTimeoutMillis(DisableTimeout)

      .decoderConfig(
        WebSocketDecoderConfig
          .newBuilder()
          .maxFramePayloadLength(maxFramePayloadLength)

          // Server's must set this to true
          .expectMaskedFrames(true)

          // Allows to loosen the masking requirement on received frames. Should NOT be set.
          .allowMaskMismatch(false)
          .allowExtensions(allowExtensions)
          .closeOnProtocolViolation(true)
          .withUTF8Validator(utf8FrameValidation)
          .build()
      )
      .build()

  private def subProtocolsCsv = subProtocols match {
    case list => list.mkString(", ")
    case Nil => null
  }
}

object WebSocketConfig {
  private val DisableTimeout = 0L
}