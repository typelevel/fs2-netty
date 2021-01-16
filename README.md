# fs2-netty

A very thin wrapper around Netty TCP sockets in terms of Cats Effect and fs2. This is designed to be a *mostly* drop-in replacement for `fs2.io.tcp`, which works directly against raw NIO. The advantages here (over raw NIO) are two-fold:

- Support for dramatically higher performance backends such as `epoll` and `io_uring`
- Access to Netty's man-millenium of workarounds for bizarre async IO issues across various platforms and architectures

The latter point is often overlooked, but it's very important to remember that NIO has an enormous number of very subtle bugs, even today. Additionally, the native asynchronous IO facilities on various operating systems *also* have a number of very subtle bugs, even down into the OS kernel layers. Netty very successfully works around all of this and has done so for over a decade. This isn't a wheel that needs to be reinvented.

## Usage

```sbt
libraryDependencies += "com.codecommit" %% "fs2-netty" % "<version>"
```

**Not production ready; barely has unit tests; please be nice.** Published for Scala 2.13.4. Probably shouldn't be published at all

```scala
import cats.effect.{IO, IOApp, ExitCode}
import fs2.netty.Network
import fs2.io.{stdin, stdout}
import java.net.InetSocketAddress

// usage: sbt "run <host> <port>"

object EchoServer extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val host = args(0)
    val port = args(1).toInt

    val rsrc = Network[IO] flatMap { net =>
      val handlers = net.server(new InetSocketAddress(host, port)) map { client =>
        client.reads.through(client.writes)
      }

      handlers.parJoinUnbounded.compile.resource.drain
    }

    rsrc.useForever.as(ExitCode.Success)
  }
}

object EchoClient extends IOApp {
  def run(args: List[String]): IO[ExitCode] = {
    val host = args(0)
    val port = args(1).toInt

    val rsrc = Network[IO] flatMap { net =>
      net.client(new InetSocketAddress(host, port)) flatMap { server =>
        val writer = stdin[IO](8096).through(server.writes)
        val reader = server.reads.through(stdout[IO])
        writer.merge(reader).compile.resource.drain
      }
    }

    rsrc.useForever.as(ExitCode.Success)
  }
}
```

The above implements a very simple echo server and client. The client connects to the server socket and wires up local stdin to send raw bytes to the server, writing response bytes from the server back to stdout. All resources are fully managed, and both processes are killed using <kbd>Ctrl</kbd>-<kbd>C</kbd>, which will cancel the respective streams and release all resource handles.

## Performance

All of this is super-duper preliminary, okay? But with very minimal optimizations, and on my laptop, the numbers roughly look like this.

### Throughput

A simple echo server, implemented relatively naively in each. The major difference is that the "raw Netty" implementation is doing things that are very unsafe in general (i.e. just passing the read buffer through to the write). You would lose a lot of that throughput if you had to actually use the data for anything other than echoing. So keeping in mind that the raw Netty implementation is effectively cheating, here you go:

|              | Raw Netty   | fs2-netty   | fs2-io     |
|--------------|-------------|-------------|------------|
| **Absolute** | 12,526 Mbps | 11,205 Mbps | 7,364 Mbps |
| **Relative** | 1           | 0.89        | 0.59       |

This was a 30 second test, echoing a long string of `x`s as fast as passible using `tcpkali`. 200 connections per second were established, up to a throttle of 500 concurrents. The relative numbers are more meaningful than the absolute numbers.

### Requests Per Second

Tested using [rust_echo_bench](https://github.com/haraldh/rust_echo_bench).

|              | Raw Netty   | fs2-netty  | fs2-io     |
|--------------|-------------|------------|------------|
| **Absolute** | 110,690 RPS | 36,748 RPS | 77,330 RPS |
| **Relative** | 1           | 0.33       | 0.70       |
