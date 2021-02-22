package fs2.netty.incudator.http

import cats.effect.kernel.Async
import cats.effect.{GenConcurrent, Resource}
import fs2.Stream
import fs2.netty.incudator.TcpNetwork
import io.netty.channel.{ChannelDuplexHandler, ChannelHandlerContext, ChannelPromise}
import io.netty.handler.codec.http._
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.util.ReferenceCountUtil

import scala.concurrent.duration.FiniteDuration

object HttpServer {

  def start[F[_]: Async](httpConfigs: HttpConfigs)(implicit
    genCon: GenConcurrent[F, Throwable]
  ): Resource[F, Stream[F, HttpClientConnection[F]]] =
    TcpNetwork()
      .server[FullHttpRequest, FullHttpResponse, Nothing](
        host = None,
        port = None,
        options = Nil,
        handlers = List(
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
        )
      )
      .map(_._2)
      .map(_.map(new HttpClientConnection[F](_)))

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
