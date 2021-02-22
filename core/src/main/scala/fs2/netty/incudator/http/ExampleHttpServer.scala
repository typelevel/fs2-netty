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

import cats.data.Kleisli
import cats.effect.{ExitCode, IO, IOApp}
import fs2.netty.incudator.http.HttpClientConnection.WebSocketResponse
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._

import scala.concurrent.duration._

object ExampleHttpServer extends IOApp {

  private[this] val HttpRouter =
    Kleisli[IO, FullHttpRequest, FullHttpResponse] { request =>
      if (request.uri() == "/health_check")
        IO {
          new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK
          )
        }
      else if (request.uri() == "/echo")
        IO {
          new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            request.content() // echo back body
          )
        }
      else
        IO {
          new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.NOT_FOUND
          )
        }
    }

  private[this] val ChatRooms =
    scala.collection.mutable.Map.empty[String, List[WebSocket[IO, Nothing]]]

  private[this] val GenericWebSocketConfig = WebSocketConfig(
    maxFramePayloadLength = 65536,
    allowExtensions = false,
    subProtocols = List.empty[String],
    utf8FrameValidation = true
  )

  private[this] val WebSocketRouter =
    Kleisli[IO, FullHttpRequest, WebSocketResponse[IO]] { request =>
      if (request.uri() == "/dev/null")
        IO {
          WebSocketResponse.SwitchToWebSocketProtocol[IO](
            GenericWebSocketConfig,
            {
              case Left(handshakeError: WebSocketHandshakeException) =>
                IO.unit

              case Left(error) =>
                IO.unit

              case Right((handshakeComplete, wsConn)) =>
                wsConn.reads.compile.drain
            }
          )
        }
      else if (request.uri() == "/echo")
        IO {
          WebSocketResponse.SwitchToWebSocketProtocol[IO](
            GenericWebSocketConfig,
            {
              case Left(handshakeError: WebSocketHandshakeException) =>
                IO.unit

              case Left(error) =>
                IO.unit

              case Right((handshakeComplete, wsConn)) =>
                wsConn.reads
                  .evalMap { // TODO: is switchMap cleaner?
                    case frame: PingWebSocketFrame =>
                      wsConn.write(new PongWebSocketFrame(frame.content()))

                    case _: PongWebSocketFrame =>
                      IO.unit

                    case frame: TextWebSocketFrame =>
                      wsConn.write(frame)

                    case frame: CloseWebSocketFrame =>
                      wsConn.write(frame)

                    case frame: BinaryWebSocketFrame =>
                      wsConn.write(frame)

                    case _: ContinuationWebSocketFrame =>
                      IO.unit
                  }
                  .attempt
                  .compile
                  .drain
            }
          )
        }
      else if (request.uri() == "/chat")
        IO {
          WebSocketResponse.SwitchToWebSocketProtocol[IO](
            GenericWebSocketConfig,
            {
              case Left(handshakeError: WebSocketHandshakeException) =>
                IO.unit

              case Left(error) =>
                IO.unit

              case Right((handshakeComplete, webSocket)) =>
                for {
                  roomId <- IO(
                    handshakeComplete
                      .requestUri()
                      .split("//?")
                      .last
                      .split("=")
                      .last
                  ) // e.g. /chat?roomId=123abc

                  _ <- IO(ChatRooms.updateWith(roomId) {
                    case Some(connections) =>
                      Some(webSocket :: connections)
                    case None =>
                      Some(List(webSocket))
                  })

                  // TODO: broadcast reads to all connections in a chat room
                } yield ()
            }
          )
        }
      else
        IO(
          WebSocketResponse
            .`4xx`[IO](404, body = None, EmptyHttpHeaders.INSTANCE)
        )
    }

  override def run(args: List[String]): IO[ExitCode] =
    HttpServer
      .start[IO](
        HttpServer.HttpConfigs(
          requestTimeoutPeriod = 500.milliseconds,
          HttpServer.HttpConfigs.Parsing.default
        )
      )
      .evalMap { httpClientConnections =>
        httpClientConnections
          .map(_.successfullyDecodedReads(HttpRouter, WebSocketRouter))
          .parJoin(65536)
          .compile
          .drain
      }
      .useForever
      .as(ExitCode.Success)

}
