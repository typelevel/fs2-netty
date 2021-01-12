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
