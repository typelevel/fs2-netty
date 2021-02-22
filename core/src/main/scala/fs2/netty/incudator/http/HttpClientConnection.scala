package fs2.netty.incudator.http

import cats.Applicative
import cats.data.Kleisli
import cats.effect.GenConcurrent
import cats.syntax.all._
import fs2.Stream
import fs2.netty.incudator.TcpSocket
import fs2.netty.incudator.http.HttpClientConnection._
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.TooLongFrameException
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler.HandshakeComplete
import io.netty.handler.codec.http.websocketx.{WebSocketFrame, WebSocketServerProtocolHandler}

// TODO: this is just a fancy function over Socket, so maybe just make this an object and a function?
// U could be io.netty.handler.timeout.IdleStateEvent if we wanted to handle connection closure, but in this
// context we want to close the channel anyway and just be notified why it was closed. However, we should likely
// send HttpResponseStatus.REQUEST_TIMEOUT for cleaner close. So change U type and handle at FS2 layer.
class HttpClientConnection[F[_]](
  tcpServerConnection: TcpSocket[
    F,
    FullHttpRequest,
    FullHttpResponse,
    Nothing
  ]
)(implicit genCon: GenConcurrent[F, Throwable]) {

  // TODO: Why does `Sync[F].delay(...).flatMap(...)` & `Stream.flatMap(...)` have a method collision when `import cats.syntax.all._`

  def successfullyDecodedReads(
    httpRouter: Kleisli[F, FullHttpRequest, FullHttpResponse],
    webSocketRouter: Kleisli[F, FullHttpRequest, WebSocketResponse[F]]
  ): Stream[F, Unit] =
    tcpServerConnection.reads
      .evalMap { request =>
        if (request.decoderResult().isFailure)
          createResponseForDecodeError(request.decoderResult().cause())
            .flatMap(tcpServerConnection.write)
        else if (isWebSocketRequest(request))
          transitionToWebSocketsOrRespond(
            webSocketRouter,
            request
          )
        else
          httpRouter(request).flatMap(tcpServerConnection.write)
      }

  private def createResponseForDecodeError(
    cause: Throwable
  ): F[DefaultFullHttpResponse] =
    Applicative[F].pure {
      cause match {
        case ex: TooLongFrameException if isTooLongHeaderException(ex) =>
          val resp = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.REQUEST_HEADER_FIELDS_TOO_LARGE
          )
          HttpUtil.setKeepAlive(resp, true)
          resp

        case ex: TooLongFrameException if isTooLongInitialLineException(ex) =>
          new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.REQUEST_URI_TOO_LONG
          )
        // Netty will close connection here

        // TODO: HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE
        case _ =>
          val resp = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.INTERNAL_SERVER_ERROR
          )
          HttpUtil.setKeepAlive(resp, false)
          resp
      }
    }

  private def transitionToWebSocketsOrRespond(
    webSocketRouter: Kleisli[F, FullHttpRequest, WebSocketResponse[F]],
    request: FullHttpRequest
  ) =
    webSocketRouter(request).flatMap {
      case WebSocketResponse.SwitchToWebSocketProtocol(
            wsConfigs,
            cb
          ) =>
        tcpServerConnection
          .mutatePipeline[WebSocketFrame, WebSocketFrame, HandshakeComplete] {
            pipeline =>
              // TODO: FS2-Netty should re-add itself back as last handler, perhaps it 1st removes itself then re-adds.
              //  We'll also remove this handler after handshake, so might be better to manually add
              //  WebSocketServerProtocolHandshakeHandler and Utf8FrameValidator since almost none of the other logic from
              //  WebSocketServerProtocolHandler will be needed. Maybe just the logic around close frame should be ported over.
              val handler =
                new WebSocketServerProtocolHandler(wsConfigs.toNetty) {
                  /*
                      Default `exceptionCaught` of `WebSocketServerProtocolHandler` returns a 400 w/o any headers like `Content-length`.
                      Let higher layer handler this. Catch WebSocketHandshakeException
                   */
                  override def exceptionCaught(
                    ctx: ChannelHandlerContext,
                    cause: Throwable
                  ): Unit = ctx.fireExceptionCaught(cause)
                }
              pipeline.addLast(handler)
              handler.channelRead(pipeline.context(handler), request)
          }
          .flatMap { connection =>
            connection.events
              .find(_ => true) // only take 1st
              .evalTap(handshakeComplete =>
                connection
                  .mutatePipeline[WebSocketFrame, WebSocketFrame, Nothing](_ =>
                    ()
                  )
                  .map(wsConn =>
                    cb(
                      (
                        handshakeComplete,
                        new WebSocket[F, Nothing](underlying = wsConn)
                      ).asRight[Throwable]
                    )
                  )
              )
              .compile
              .drain
          }
          .onError { case e =>
            cb(e.asLeft[(HandshakeComplete, WebSocket[F, Nothing])])
          }
          .void

      case WebSocketResponse.`3xx`(code, body, headers) =>
        wsResponse(code, body, headers).flatMap(tcpServerConnection.write)

      case WebSocketResponse.`4xx`(code, body, headers) =>
        wsResponse(code, body, headers).flatMap(tcpServerConnection.write)

      case WebSocketResponse.`5xx`(code, body, headers) =>
        wsResponse(code, body, headers).flatMap(tcpServerConnection.write)
    }

  private def wsResponse(
    code: Int,
    body: Option[String],
    headers: HttpHeaders
  ): F[FullHttpResponse] =
    Applicative[F].pure(
      new DefaultFullHttpResponse(
        HttpVersion.HTTP_1_1,
        HttpResponseStatus.valueOf(code),
        body.fold(Unpooled.EMPTY_BUFFER)(s =>
          Unpooled.wrappedBuffer(s.getBytes())
        ),
        headers,
        EmptyHttpHeaders.INSTANCE
      )
    )
}

object HttpClientConnection {

  private def isWebSocketRequest(request: FullHttpRequest): Boolean = {
    // this is the minimum that Netty checks
    request.method() == HttpMethod.GET && request
      .headers()
      .contains(HttpHeaderNames.SEC_WEBSOCKET_KEY)
  }

  private def isTooLongHeaderException(cause: TooLongFrameException) =
    cause.getMessage.contains("header")

  private def isTooLongInitialLineException(cause: TooLongFrameException) =
    cause.getMessage.contains("line")

  sealed abstract class WebSocketResponse[F[_]]

  object WebSocketResponse {

    // One of throwable could be WebSocketHandshakeException
    final case class SwitchToWebSocketProtocol[F[_]](
      wsConfigs: WebSocketConfig,
      cb: Either[Throwable, (HandshakeComplete, WebSocket[F, Nothing])] => F[
        Unit
      ]
    ) extends WebSocketResponse[F]

    // TODO: refined types for code would be nice
    final case class `3xx`[F[_]](
      code: Int,
      body: Option[String],
      headers: HttpHeaders
    ) extends WebSocketResponse[F]

    final case class `4xx`[F[_]](
      code: Int,
      body: Option[String],
      headers: HttpHeaders
    ) extends WebSocketResponse[F]

    final case class `5xx`[F[_]](
      code: Int,
      body: Option[String],
      headers: HttpHeaders
    ) extends WebSocketResponse[F]

  }

}
