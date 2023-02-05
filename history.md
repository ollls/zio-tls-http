# Update history.

* Update(02-02-2023) New processing code for incoming data ( http body ZStream without ZStream.peel ). This was done due to an issue when ZStream2 peel attempts to read more data then required when size of the "peeled" stream is limited with ZStream.take( content-len).

* Update(01-20-2023) ZIO.log with logback, access log with logback and ZIO aspects. Original custom logger removed.

* Update(01-19-2023) zio-http2 first public release: https://github.com/ollls/zio-quartz-h2

* Update(12-12-2022) ZIO2 async NIO: TcpServer and TLSServer run socket group on default ZIO thread pool. Pushed up to master_zio2.

* Update(12-11-2022) ZIO2 Branches dev_zio2 and master_zio2 are active with ZIO 2.0.5. ZIO 2.0.5 has improved performance for async ops. New network layer based on netio is under testing now, no known problems so far. <br>in /examples: SyncTLSSocketServer is active now, to enable Java-NIO async uncomment TLSServer.

* Update ( 05-03-2022 ) branch 2.0.CURRENT with ZIO2-RC6, no more connection leak issue with RC6. ZStream.peel was fixed in RC6.

* Update ( 04-21-2022 ) branch dev_zio2_rc5 ZIO2 RC5 port.

* Update ( 02-15-2022 ) branch dev_zio2 can be built now, not tested and profiled yet, more to come.

* Use cases slides: https://github.com/ollls/zio-tls-http-slides

* DEV 1.2-m3 Websocket support moved to ZStream interface. See, example: for "/ws-test"

* DEV 1.2-m3 fixed compatibility issue on 1.1 websocket with 1.2 server streams. Prep, for ZStream websocket upgrade.

* DEV 1.2-m2 many useful fixes, Response ZStream now works with ensuring() which is dependent on user's environment.

* DEV 1.2-m1 (streams) is out.

* DEV is 1.2-m1, HttpClient on ZStream, file streaming, data streaming with chunked encoding support in and out.
  https://github.com/ollls/zio-tls-http/blob/dev/FileStreaming.png  

* DEV is 1.2-m1, everything now on ZStream, chunked encoding in/out support. ZStream conversion for http client is coming. 
  All the documentation for 1.2 with ZIO streams is outdated now, this will be fixed.

* 1.1.0-m8 release, with server stop(), header iterrator, log iterrator, pattern match on Channel -> Tls/Tcp Channel subclasses, 
  DDOS patch to block and log bad TLS handshakes.
  
* prototype of performance load test tool with ZIO - https://github.com/ollls/zio-wrk

* Quartz server template with m5 ( memory caching server with ZIO Magic and TLS Client based on ZIO effects ).<br>

https://github.com/ollls/zio-quartz-ee

* Template ( hello world projects, plain and TLS respectively )

https://github.com/ollls/hello-http

https://github.com/ollls/hello-https
  

* ZIO Env type parameters for web filters and combinations of filters, some test cases on filter combinations with various environments.

       val proc11 = WebFilterProc(
          (_) => for {
          a    <- ZIO.access[Has[String]]( attr => attr  ) 
        } yield( Response.Ok.hdr( "StringFromEnv" -> a.get ))
  etc... please see MyServer.scala to learn more.
  
              val proc3 = proc1 <> proc2 <> proc11  

  
* "MyLogging.PRINT_CONSOLE = false" will supress output to terminal, data will go only to colsole.log
