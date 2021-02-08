# FS2-Netty Developer Docs

## What is FS2-Netty
A general networking library for TCP and UDP based protocols over the Netty framework using typesafe FS2 streams.
It should be general enough to support protocols such as DNS, FTP, MQTT, Redis, HTTP/1.1/ HTTP/2, HTTP/3, Quic, etc.

(M. Mienko - I don't know FS2, so cannot comment on its role in detail. I will just write "stream" or "queue" where-ever makes sense :) )

## Key Questions for FS2-Netty
1) (API) How much of Netty should this library expose to users? 
   - How to expose Netty's pipeline, event-loop scheduler, channel, channel handlers, etc. such that users the
     flexibility of Netty's pipeline and typesafe FP abstractions.
   - At a minimum, stop exposing Netty pipeline after we produce `Stream[Socket]` 
2) (Safety) When Netty constructs are exposed, how can it do safely do so? Safety is defined as:
   - preventing users from crashing clients or servers 
   - preventing leaking resources such as connections or memory 
   - mitigating negative performance, namely around the socket operations
   - prevent unintentionally protocol violations, if the connection uses a websocket protocol, then app should never
     send an HTTP or Redis message. Likewise, TCP and UDP shouldn't mix.
3) (Liveliness) When an app creates a client or server and the "world" is happy (networking layer is working and
   peer(s) are up), then how does it ensure messages are always received or sent?
4) (Robustness) When a peer goes down, there's a malicious peer, network fails, protocol is violated,
   or machine resource usage is high, then how does the framework:
   - gracefully handle these scenarios itself (auto-recover if possible and makes sense), or
   - permit the user to handle them gracefully, or 
   - notify the user of this non-fatal error?
5) (Visibility) ...
6) (Efficiency + Performance) ...

## Use Cases
Below are the use-cases FS2-Netty needs to support.

### A Pub-Sub like System
This system uses the websocket protocol to integrate with clients. On the backend, messages can be sent via HTTP/1.1.
Published events from clients are arbitrarily processed. The following are requirements of a networking framework from
the point of view of developers building the pub-sub system. 

#### High Level Requirements
HTTP:
- We want a simple (GET & POST) but robust (handles malformed requests) HTTP server that handles payloads on the order of KB's.
- We want HTTP connections to be efficient for client use, i.e. a client can pool connections and reuse them for multiple HTTP requests.
- We want the HTTP server to avoid leaking connections; it should detect and clean up dropped connections.
- We want the HTTP server to rotate client connections to load balance across multiple instances.
- We don't want to allow HTTP pipelining.
- We want HTTP logging (and metrics?).
  - Access log with URI path, HTTP method, HTTP headers, payload size in bytes, response time, response code, and response headers.
  - Metrics on how often the TCP connection is closed by the server and the client. How often client drops connections? 
- We don't need SSL since this service will be behind a Load Balancer that will terminate SSL connections.
  
WebSocket (at a minimum): 
- We want the HTTP connection to transition to an arbitrary WebSocket connection, i.e. there's not a single WS controller with a single path for the whole server.
- We want to receive websocket frames.
  - We want to backpressure the sender. For context, say our service proxies frames to other tcp servers. Furthermore,
    one of those servers is slow. We want the client to slow down their rate without an explicit higher layer protocol
    message. However, we can optimize this so that a single slow downstream does backpressure all other frames going to
    other destinations. We maintain a queue of frames and only backpressure when queue is full. (Without an explicit
    higher layer flow control or protocol other TCP, this will only mitigate the problem. Frames for different
    downstreams are multiplexed over a single TCP connection, so once queue is full because a single slow consumer than 
    we block all other frame processing, a.k.a Head-of-line (HOL) blocking. This is partly a fundamental limitation of
    TCP, i.e. there's no virtual streams. Such limitations are overcome in QUIC and HTTP/3.)
    Either of these 2 approaches may work:
    - Only read socket when we finish processing a message. Pub-Sub app may queue up the frame for later processing, or it may immedieiately process it.
    - Give FS2-Netty a queue to fill and only backpressure when queue is full (or above a high watermark, start reading when below a low watermark).
- We want to send ws frames. 
  - When sending any frame, we want to know if it was written to the connection. 
  - When sending a close frame, then the frame itself should be sent, then the connection should be closed.
  - We want to know if we're writing too fast and respond to backpressure (namely TCP backpressure).
- We want to know why a websocket connection closed, what was the close code if any.

WebSocket (extras that will save us time dev time if framework can do these):
- We want to group connections, label an individual connection, write to a single connection, broadcast to group, close a single connection, and close all connections.
- We want to know if connection is still alive.
  - Close connection if it's unresponsive.
- We want metrics for WS server
  - Count of different frame types
  - The size of data frames
  - Count of different close codes
    
General Server configs:
- We want to limit the number of TCP connections? To protect the server?
    - Backpressure accepting connections (and reads) if memory is getting high?
      E.g. Netty thread pool is queuing too many tasks in compute thread pool. Queue will hit OOM.

## Implementation thoughts for above use cases
Based on what currently exists and past discussions with additional modifications.

## Package Structure
- fs2-netty-core
  - Netty
    - wrappers
    - ops
- fs2-netty-server
  - tcp
  - udp
- fs2-netty-client
  - tcp
  - udp
- fs2-netty-all
- fs2-netty-simple-http-server
  - websockers

### API
We'll look at how intermediate library/module authors will use FS2-Netty to build protocols for above use-cases. 

#### TCP Server
The idea is that FS2-Netty users provide a custom Netty pipeline and probably some configs to `FS2TcpServer`, which
builds the server resource that exposes a Stream of `Socket`'s. Then users can map over that to produce their desired
api. `Socket` should be the bridge out of Netty, i.e. no more Netty further exposed (maybe this isn't practical,
there's also more things Netty does besides sockets, so api will be refined). 
```scala
// similar to SocketHandler, a bridge out of Netty that exposes streams for I/O types and other TCP connection api methods 
class NettyPipeline[I, O](handlers: List[NettyHandler])

object FS2TcpServer {
  def make[F[_], I, O](pipeline: NettyPipeline[I, O]): Resource[F, Fs2Stream[F, ServerSocket[F, I, O]]] = ???
}

object MySimpleHttpServer {

  private trait HttpSocket[F[_]] {
    def read(f: HttpRequest => F[HttpResponse]): F[Unit]
  }

  private[this] val httpPipeline = new NettyPipeline[HttpRequest, HttpResponse](handlers = Nil) // This would contain actual handlers

  def make[F[_]](): Resource[F, Fs2Stream[F, HttpSocket]] =
    FS2TcpServer.make(httpPipeline)
      .map(stream => stream.map(tcpServerSocket => new HttpSocket[F] {
        override def read(f: HttpRequest => F[HttpResponse]): F[Unit] = ??? // interface with tcpServerSocket
      }))
}
```

# Random extras
```scala
abstract class WebSocketFrame(underlying: NettyWebSocketFrame)
trait DataFrame extends WebSocketFrame
trait ControlFrame extends WebSocketFrame

final case class BinaryFrame() extends DataFrame
final case class TextFrame() extends DataFrame
// Pipeline Framework may also handle this, but we should expose for advanced usage 
final case class ContinuationFrame() extends DataFrame

final case class PingFrame() extends ControlFrame
final case class PongFrame() extends ControlFrame
final case class CloseFrame() extends ControlFrame

trait CloseReason
final case class ConnectionError(cause: Throwable) extends CloseReason
case object TcpConnectionDropped extends CloseReason
final case class FrameworkInitiated(closeFrame: CloseFrame) extends CloseReason
final case class UserInitiated(closeFrame: CloseFrame) extends CloseReason

// Or Observer
trait WebSocketListener[F[_]] {
   def connectionOpened(
     subprotocol: Option[String],
     headers: NettyHttpHeaders)(
     implicit context: NettyWebSocketContext
   ): F[Unit]
  
  def connectionHandshakeError(
    cause: Throwable
  ): F[Unit]
  
  def received(frame: WebSocketFrame)(
    implicit context: NettyWebSocketContext
  ): F[Unit]
  
  def receivedAggregated(frame: DataFrame)(
    implicit context: NettyWebSocketContext
  ): F[Unit]
  
  def connectionBackPressured()
  
  def connectionClosed(reason: CloseReason): F[Unit]
}
```
