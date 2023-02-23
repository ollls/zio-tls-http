

[![Generic badge](https://img.shields.io/badge/release-2.0.0-blue)](https://repo1.maven.org/maven2/io/github/ollls/zio-tls-http_3/2.0.0/)
[![Generic badge](https://img.shields.io/badge/Nexus-v1.2--m3-yellow.svg)](https://repo1.maven.org/maven2/io/github/ollls/zio-tls-http_2.13/1.2-m3/)
[![Generic badge](https://img.shields.io/badge/Nexus-v1.1.0--m8-blue.svg)](https://repo1.maven.org/maven2/io/github/ollls/zio-tls-http_2.13/1.1.0-m8)
<br>
[![Generic badge](https://img.shields.io/badge/Hello%20World-template-red)](https://github.com/ollls/hello-http)
<br>
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ollls_zio-tls-http&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ollls_zio-tls-http)


# Lightweight Scala TLS HTTP 1.1 Web Server based on ZIO async fibers and Java NIO sockets.
Web Server has its own implementation of TLS protocol layer based on JAVA NIO and standard JDK SSLEngine. Everything is modeled as ZIO effects and processed as async routines with Java NIO. Java NIO and Application ZIO space uses same thread pool for non-blocking operations.
Server implements a DSL for route matching, it's very similar (but a bit simplified) to the one which is used in HTTP4s. Server implements pluggable pre-filters and post-filters. The goal is to provide small and simple HTTP JSON server with all the benefits of async monadic non-blocking JAVA NIO calls wrapped up into ZIO interpreter with minimal number of dependencies.

# ZIO2 release.
Necessary dependencies(check hello-http template):
```scala
   "dev.zio" %% "zio" % "2.0.x",
   "io.github.ollls" %% "zio-tls-http" % "2.0.0",
```

* Native ZStream2 with ZIO2.
* Integration with http1.1 chunked.
* Special http multi-part ZStream.
* Support for ZIO2 logging with logback.
* Separate http access log with rotation.
* App template: "hello-http" with major use cases.
* Version of ZIO library can be configured in build.sbt on app template by the user.

Appreciate any feedback, please use, my email or open issue, or use 
https://discord.com/channels/629491597070827530/817042692554489886   ( #zio-tls-http ) 
<br>

> **Also: Please check out** https://github.com/ollls/quartz-h2  https://github.com/ollls/zio-quartz-h2

To run from sbt:  "sbt example/run". <br>
Example file: https://github.com/ollls/zio-tls-http/blob/master_zio2/examples/start/src/main/scala/MyServer.scala

```scala
package example
import zio.logging.backend.SLF4J
import zio.{ZIO, Chunk}
import zhttp.Method._
import zhttp.dsl._
import zhttp.{TLSServer, TcpServer, HttpRoutes}
import zhttp.{MultiPart, Headers, ContentType, Response, FileUtils}

object MyApp extends zio.ZIOAppDefault {

  override val bootstrap =
    zio.Runtime.removeDefaultLoggers ++ SLF4J.slf4j ++ zio.Runtime.enableWorkStealing
    
  val r = HttpRoutes.of { case GET -> Root / "health" =>
    ZIO.attempt(Response.Ok().asTextBody("Health Check Ok"))
  }
  val myHttp =
    new TcpServer[Any](port = 8080, keepAlive = 2000, serverIP = "0.0.0.0")
  val run = myHttp.run(r)
}

```
To enable more detailed logging, use logback-test.xml with "debug" or "trace" levels

```xml
  <root level="debug">
    <appender-ref ref="STDOUT" />
  </root>
```


