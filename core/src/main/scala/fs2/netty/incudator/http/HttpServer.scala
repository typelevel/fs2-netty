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

import cats.data.NonEmptyList
import cats.effect.{Async, Resource}
import cats.syntax.all._
import fs2.Stream
import fs2.netty.Network
import fs2.netty.pipeline.socket.Socket
import io.netty.handler.codec.http._
import io.netty.handler.timeout.ReadTimeoutHandler

import scala.concurrent.duration.FiniteDuration

object HttpServer {

  implicit val decoder = new Socket.Decoder[FullHttpRequest] {

    override def decode(x: AnyRef): Either[String, FullHttpRequest] = x match {
      case req: FullHttpRequest => req.asRight[String]
      case _ => "non http message, pipeline error".asLeft[FullHttpRequest]
    }
  }

  def start[F[_]: Async](
    httpConfigs: HttpConfigs
  ): Resource[F, Stream[F, HttpClientConnection[F]]] =
    for {
      network <- Network[F]

      rawHttpClientConnection <- network
        .serverResource[FullHttpResponse, FullHttpRequest](
          host = None,
          port = None,
          handlers = NonEmptyList.of(
            new HttpServerCodec(
              httpConfigs.parsing.maxInitialLineLength,
              httpConfigs.parsing.maxHeaderSize,
              httpConfigs.parsing.maxChunkSize
            ),
            new HttpServerKeepAliveHandler,
            new HttpObjectAggregator(
              httpConfigs.parsing.maxHttpContentLength
            ),
            new ReadTimeoutHandler( // TODO: this also closes channel when exception is fired, should HttpClientConnection just handle that Idle Events?
              httpConfigs.requestTimeoutPeriod.length,
              httpConfigs.requestTimeoutPeriod.unit
            )
            // new HttpPipeliningBlockerHandler
          ),
          options = Nil
        )
        .map(_._2)

    } yield rawHttpClientConnection.map(new HttpClientConnection[F](_))

  /**
    * @param requestTimeoutPeriod - limit on how long connection can remain open w/o any requests
    */
  final case class HttpConfigs(
    requestTimeoutPeriod: FiniteDuration,
    parsing: HttpConfigs.Parsing
  )

  // TODO: what about `Int Refined NonNegative` or validated or custom value types?
  object HttpConfigs {

    /**
      * @param maxHttpContentLength - limit on body/entity size
      * @param maxInitialLineLength - limit on how long url can be, along with HTTP preamble, i.e. "GET HTTP 1.1 ..."
      * @param maxHeaderSize        - limit on size of single header
      */
    final case class Parsing(
      maxHttpContentLength: Int,
      maxInitialLineLength: Int,
      maxHeaderSize: Int
    ) {
      def maxChunkSize: Int = Parsing.DefaultMaxChunkSize
    }

    object Parsing {

      private val DefaultMaxChunkSize: Int =
        8192 // Netty default

      val DefaultMaxHttpContentLength: Int =
        65536 // Netty default

      val DefaultMaxInitialLineLength: Int =
        4096 // Netty default

      val DefaultMaxHeaderSize: Int = 8192 // Netty default

      val default: Parsing = Parsing(
        DefaultMaxHttpContentLength,
        DefaultMaxInitialLineLength,
        DefaultMaxHeaderSize
      )
    }

  }

}
