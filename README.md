ZIO 1.0 with ZStream 1.0<br>
[![Generic badge](https://img.shields.io/badge/Nexus-v1.2--m3-yellow.svg)](https://repo1.maven.org/maven2/io/github/ollls/zio-tls-http_2.13/1.2-m3/)
<br>
ZIO 1.0 without ZStream<br>
[![Generic badge](https://img.shields.io/badge/Nexus-v1.1.0--m8-blue.svg)](https://repo1.maven.org/maven2/io/github/ollls/zio-tls-http_2.13/1.1.0-m8)
<br>
<br>
[![Generic badge](https://img.shields.io/badge/Hello%20World-template-red)](https://github.com/ollls/hello-http)
[![Generic badge](https://img.shields.io/badge/ZIO--LDAP-bindings-blue)](https://github.com/ollls/zio-quartz-ee/blob/main/src/main/scala/zio-quartz/clients/async-ldap.scala)

Appreciate any feedback, please use, my email or open issue, or use 
https://discord.com/channels/629491597070827530/817042692554489886   ( #zio-tls-http ) 
<br>

> **Also: Please check out** https://github.com/ollls/quartz-h2 

To run from sbt:  "sbt example/run". <br>
Example file: "/zio-tls-http/examples/start/src/main/scala/MyServer.scala"
<br>
https://github.com/ollls/zio-tls-http/blob/dev/examples/start/src/main/scala/MyServer.scala

DEV on 1.2-m3

# Update history.

* Update(02-02-2023) Mew incoming processing code ( Http body ZStream without ZStream.peel ). This was done due to an issue when ZStream2 peel attempts to read more data then required and size of stream was already was limited with ZStream.take( content-len).

* Update(20-01-2023) ZIO.log with logback, access log with logback and ZIO aspects. Original custom logger removed.

* Update(19-01-2023) zio-http2 first public release: https://github.com/ollls/zio-quartz-h2

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

## Use cases.

* Note on how stuff works.
 https://github.com/ollls/zio-tls-http/blob/dev/doc/HowChannelsWork.txt


# Lightweight Scala TLS HTTP 1.1 Web Server based on ZIO async fibers and Java NIO sockets.

![alt text](https://github.com/ollls/zio-tls-http/blob/master/Screenshot.jpg)


Your comments or questions appreciated, please ask at
https://gitter.im/zio-tls-http

## How to run.

Import https://github.com/ollls/zio-tls-http/blob/master/localhost.cer to keychain on MacOS.
Click on localhost once imported, find ">Trust", expand it, select "Always Trust".
On Windows or any other machine, you will need to go thru similar steps.
You can use any other cert, as long as keystore.jks is seen by the server.


Please, use docker image to run it or use sbt run, for sbt run default TLS port is 8084, in docker image it will be mapped to 443.
To run in docker:

    sbt package
    docker build --pull --rm -f "Dockerfile" -t ziohttp:latest "."
    docker container run --name zhttp_container -p 443:8084 ziohttp:latest

    https://localhost        ZIO doc static web server example
    https://localhost/print  print headers example
    https://localhost/test   JSON output example
    
    Use case examples:
    https://github.com/ollls/zio-tls-http/blob/master/src/main/scala/MyServer.scala

    
Server will use self-signed SSL certificate, you will need to configure the browser to trust it.
Certificate resides in keystore.jks

## Quick points

### Look at https://github.com/ollls/zio-tls-http/blob/master/src/main/scala/MyServer.scala
Scroll to the bottom, you will see server startup code, then route initialization code and the actual routes ( scala partial function ) examples.

### Useful example of quick Routing shortcut, with reference to Request.

```scala
val quick_req = HttpRoutes.of {
    case req @ GET -> Root / "qprint" => ZIO( Response.Ok().asTextBody( req.headers.printHeaders) )
}
```    
    
### chunked transfer encoding in not supported.   

### multipart/form-data is not supported.
^*Hacking is encourged to address those things, if any interest ...*

### Server was tested with large files upload/download presented as byte streams.

## Approach. 

The goal is to provide small and simple HTTP JSON server with all the benefits of async monadic non-blocking JAVA NIO calls wrapped up into ZIO interpreter with minimal number of dependencies.

Here all the dependencies it uses: This includes only ZIO and extremely fast JSON converter.

( from Dockerfile )

    COPY  /lib_managed/jars/dev.zio/zio_2.13/zio_2.13-1.0.3.jar ./lib
    COPY  /lib_managed/jars/dev.zio/izumi-reflect_2.13/izumi-reflect_2.13-1.0.0-M9.jar ./lib
    COPY  /lib_managed/jars/dev.zio/izumi-reflect-thirdparty-boopickle-shaded_2.13/izumi-reflect-thirdparty-boopickle-shaded_2.13-1.0.0-M9.jar ./lib
    COPY  /lib_managed/jars/dev.zio/zio-stacktracer_2.13/zio-stacktracer_2.13-1.0.3.jar ./lib
    COPY  /lib_managed/jars/com.github.plokhotnyuk.jsoniter-scala/jsoniter-scala-core_2.13/jsoniter-scala-core_2.13-2.6.2.jar ./lib
    COPY  /lib_managed/jars/com.github.plokhotnyuk.jsoniter-scala/jsoniter-scala-macros_2.13/jsoniter-scala-macros_2.13-2.6.2.jar ./lib

## Overview.
Web Server has its own implementation of TLS protocol layer based on JAVA NIO and standard JDK SSLEngine. Everything is modeled as ZIO effects and processed as async routines with Java NIO. Java NIO and Application ZIO space uses same thread pool for non-blocking operations.
Server implements a DSL for route matching, it's very similar (but a bit simplified) to the one which is used in HTTP4s. Server implements pluggable pre-filters and post-filters.
Server has two types of application routes, so called: channel routes and app routes. Channel routes allows to write a code without fetching complete body into memory and Application routes provide more convenient interface for basic JSON work where Response and Request body is read in fetched as ZIO Chunk. Also, it implements static HTTP file server and was extensively tested as such. Currently for file operations examples we don't use nio calls and we don't use Managed or any other resource bracketing, this will be added later.

## State of the project. (testing, performance, etc )
Performance tests are under way, but expectation is that on core i9 machine, simple JSON encoding GET call can be done in up to 20 000 TPS. 

## JSON encoding.
It uses https://github.com/plokhotnyuk/jsoniter-scala  ( now with ZIO-JSON )

HTTP Request has

```scala
def fromJSON[A](implicit codec:JsonValueCodec[A]) : A = {
                     readFromArray[A]( body.toArray )
}
```
                
HTTP Response has

```scala
def asJsonBody[B : JsonValueCodec]( body0 : B ) : Response = { 
  val json = writeToArray( body0 )
  new Response(this.code, this.headers, Some( Chunk.fromArray( json ))).contentType( ContentType.JSON) 
}
```


## Logs.

Logs implemented with ZIO enironment and ZQueue. Currently there are only two logs: access and console.

Log rotation uses two configuration constants:

        object MyLogging {
             val  MAX_LOG_FILE_SIZE = 1024 * 1024 * 10 //10M
             val  MAX_NUMBER_ROTATED_LOGS = 4
             ....



You can specify desired loglevel on server initialization.
By default, log with name "console" will print color data on screen.
Also, "access" log will duplicate output to console if console LogLevel.Trace.
To avoid too many messages being posted to console, just increase "console" LogLevel.

```scala
myHttp
  .run(myHttpRouter.route)
  .provideSomeLayer[ZEnv](MyLogging.make(("console" -> LogLevel.Trace), ("access" -> LogLevel.Info)))
  .exitCode
}
```
  
  
  You can add more logs as a Tuple, for example: ("myapplog" -> LogLevel.Trace )
  Then just call the log by name in for comprehension on any ZIO.
  
  ```scala
    _    <- MyLogging.info( "myapplog", s"TLS HTTP Service started on " + SERVER_PORT + ", ZIO concurrency lvl: " + metr.get.concurrency + " threads")
  ```
    
   "logname" will be maped to logname.log file, object MyLogging has the relative log path.
   
   ```scala
   object MyLogging { val  REL_LOG_FOLDER = "logs/" .... }
   ```
   

## Route matching DSL by examples.

#### Basic example

- Simple route returning http code Ok with text body.

  ```scala
  val appRoute1 = HttpRoutes.of {
        case GET -> Root / "hello" => ZIO(Response.Ok.asTextBody("Hello World"))
  }
  ```
      
- Simple JSON response      
      
  ```scala    
  val another_app = HttpRoutes.ofWithFilter(proc1) { req =>
        req match {
            case GET -> Root / "test" =>
                ZIO(Response.Ok.asJsonBody( DataBlock("Thomas", "1001 Dublin Blvd", 
                                           Array( "Red", "Green", "Blue"))) )
        }   
  }
  ```      
  
- How to read from JSON represented by case class  
      
  ```scala    
  case POST -> Root / "test" => 
     ZIO.effect { //need to wrap up everything in the effect to have proper error handling
       val db : DataBlock = req.fromJSON[DataBlock]
       val name = db.name
       Response.Ok.asTextBody( s"JSON for $name accepted" )     
     }                                  
  }
  ```    

- Example with cookies, path and variable parameters.

  *Please, note a raw param string is always available with req.uri.getQuery*

  ```scala
  object param1 extends QueryParam("param1") 
  object param2 extends QueryParam("param2")

  val app_route = HttpRoutes.of { req: Request =>
  {
    req match {
        val app_route = HttpRoutes.of { req: Request => { req match {
        case GET -> Root / "hello" / "user" / StringVar(userId) :? param1(par) :? param2(par2) =>
        ZIO(  Response.Ok
                  .hdr(headers)
                  .cookie( Cookie("testCookie", "ABCD", secure = true )
                  .body( s"$userId with param1 = $par, param2 = $par2\n query = $query" ))
  }
  ...
   ```
      
- How to post a file with AppRoute ( file will be pre-read in memory for AppRoute integration )

  ```scala
     ...
     case POST -> Root / "receiver" / StringVar(fileName) =>
        effectBlocking {
          val infile = new java.io.FileOutputStream( ROOT_CATALOG + "/" + fileName)
          infile.write(req.body.toArray)
          infile.close()
        }.refineToOrDie[Exception] *> ZIO(Response.Ok)
  ```

## Filters and composition of filters.

 Web filter is a simple function:  Response => ZIO( Request ). Inside of the web filter a decision can be made whether to allow access to resource or return HTTP error code.
 If you chain several filters with "<>" chain will be interrupted once a non 2xx code will be returned by at least one of the filters in the chain.
 
 Defining two web filters, they will be called before any user defined app route logic.

```scala
val proc1 = WebFilterProc( (_) => ZIO(Response.Ok.hdr("Injected-Header-Value" -> "1234").hdr("Injected-Header-Value" -> "more" ) ) )

val proc2 = WebFilterProc( req  => ZIO {
               if ( req.headers.getMval( "Injected-Header-Value").exists( _ == "1234" ) )
               Response.Ok.hdr("Injected-Header-Value" -> "CheckPassed") 
               else Response.Error( StatusCode.Forbidden ) 
                      } )
 ```                             
 
 Here we combine proc1 and proc2 together.
 
 ```scala
 val proc3 = proc1 <> proc2 
 ```     
 
 Filters can be assigned per each app route, exactly same way as we did with HttpRoutes.of() but with ofWithFilter(). 
 Appropriate filter will be called only if route matches, there is a special logic which build a final route function out of a filter and user defined app route partial function. 
 
 Example:
 
 ```scala
val another_app = HttpRoutes.ofWithFilter(proc3) {
    case GET -> Root / "test" =>
        ZIO(Response.Ok.contentType(ContentType.JSON).body(DataBlock("Name", "Address")))
}
```      

## Post filters.
Post filters are different from pre-filters described earlier. 
The goal of Post-Filter is to provide extra data in the form of http headers in the user output. ( such as CORS headers ).
Currently post filter is a simple:

```scala
  type PostProc      = Response[_] => Response[_]

  val openCORS: HttpRoutes.PostProc = (r) => r.hdr(("Access-Control-Allow-Origin" -> "*"))
                                        .hdr(("Access-Control-Allow-Method" -> "POST, GET, PUT"))
```
                                            
Post filters are used same way:

```scala
val another_app2 = HttpRoutes.ofWithFilter(proc3, openCORS) { req =>
  req match {
    case GET -> Root / "test2" =>
      ZIO(Response.Ok.contentType(ContentType.Plain).body(req.headers.printHeaders))
  }
}
```         
         
  or

```scala 
val another_app2 = HttpRoutes.ofWithPostProc( openCORS) { req =>
  req match {
    case GET -> Root / "test2" =>
      ZIO(Response.Ok.contentType(ContentType.Plain).body(req.headers.printHeaders))
  }
}
```
         

## Default filters.

As shown in the example, file MyServer.scala.

```scala
HttpRoutes.defaultFilter( (_) => ZIO( Response.Ok().hdr( "default_PRE_Filter" -> "to see me use print() method on headers") ) )
HttpRoutes.defaultPostProc( r => r.hdr( "default_POST_Filter" -> "to see me check response in browser debug tool") )
```
    
This should be self-explanatory. Expectation is that default filters always be called, either standaolne or in composition with custom filers provided on routes.    
    
That behavior achieved with follwing lines in HttpRoutes.scala.

```scala
def ofWithFilter(
     filter0: WebFilterProc,
     postProc0: PostProc = _postProc
)(pf: PartialFunction[Request, ZIO[ZEnv with MyLogging, Throwable, Response]]): HttpRoutes[Response] = {

    //preceded with default filter first
    val filter   = if ( filter0   != _filter )  _filter <> filter0  else filter0

    // default post proc called last, defaultPostProc ( mypostProc( response )
    val postProc = if ( postProc0 != _postProc ) _postProc compose postProc0 else postProc0
```



## Channel routes and example of static web server.

Channel routes do nothing but provide raw "ch" : channel in response, so user is responsible for reading and processing data blocks as they come.
There a simple static Web Server implemented based on that concept. It was used for access to ZIO documentation and tested with complex snapshots of several web sites.
Channel is available from Request::ch, with two simple functions:

```scala
def read: ZIO[ZEnv, Exception, Chunk[Byte]]
def write(chunk: Chunk[Byte]): ZIO[ZEnv, Exception, Int]
```
  

Here is a static web server example with channel routes. It serves 3 catalogs with different documents. It accepts file uploads to "/save" without preloading them into memory.
Please, note matching operator "/:" - which means all the subfolders under provided folder.
For GET requests we are not interested in getting data by chunks, so we complete get requests with service function finishBodyLoadRequests() called explicitly.

```scala
val raw_route = HttpRoutes.of { req: Request =>
  {
      req match {
      case GET -> Root =>
        for {
          _ <- myHttpRouter.finishBodyLoadForRequest(req)
          res <- ZIO(
                  Response
                    .Error(StatusCode.SeeOther)
                    .body("web/index.html")
                )
        } yield (res)
      case GET -> "web" /: _ =>
        myHttpRouter.finishBodyLoadForRequest(req) *>
          FileUtils.loadFile(req, ROOT_CATALOG)

      case GET -> Root / "web2" / _ =>
        myHttpRouter.finishBodyLoadForRequest(req) *>
          FileUtils.loadFile(req, ROOT_CATALOG)

      case GET -> "web3" /: _ =>
        myHttpRouter.finishBodyLoadForRequest(req) *>
          FileUtils.loadFile(req, ROOT_CATALOG)

      case POST -> Root / "save" / StringVar(_) =>
        FileUtils.saveFile(req, ROOT_CATALOG)

    }
  }
}
```


## Client connections resource pools.

* dev_svc tested ResPoolGroup, connection pool for ZIO environment: support many resources of the same type, with access by name.
  Example:

          val ldap2 : ZLayer[zio.ZEnv with MyLogging,Nothing,Has[ResPoolGroup.Service[LDAPConnection]]]= ResPoolGroup.make[LDAPConnection]( 
                     ResPoolGroup.RPD( AsyncLDAP.ldap_con_ssl, AsyncLDAP.ldap_con_close, "ldap_pool"),
                     ResPoolGroup.RPD( AsyncLDAP.ldap_con_ssl2, AsyncLDAP.ldap_con_close2, "temp_pool" ) ) 
                     
   Usage:
   
          case GET -> Root / "ldap" =>
          for {
              con  <- ResPoolGroup.acquire[LDAPConnection]( "ldap_pool")
              res  <- AsyncLDAP.a_search( con, "o=company.com", "uid=user2")
              _    <- ResPoolGroup.release[LDAPConnection] ( "ldap_pool", con  )
           } yield( Response.Ok.asJsonBody( res.map( c => c.getAttributeValue( "cn" ) ) ) )

* dev_svc branch has new environments ResPool[] and ResPoolGroup[], used with LDAPConnecton from Unbound LDAP SDK with async ZIO binding.
ResPool[] uses short lived connection, con will be closed in 10 sec if not used. This way you get conection pool with reliable recovery.


        case GET -> Root / "ldap" =>
                for {
                        con  <- ResPool.acquire[LDAPConnection] 
                        res  <- AsyncLDAP.a_search( con, "o=company.com", "uid=userid")
                        _    <- ResPool.release[LDAPConnection] ( con )
                } yield( Response.Ok.asJsonBody( res.map( c => c.getAttributeValue( "cn" ) ) ) )

^Can be used as example how to do ZIO Env with type parameters. ( you will need some Izumi's zio.tag to make it work, Java type earsure blocks nested types, and Has[] was made invariant)

https://github.com/ollls/zio-tls-http/blob/dev_svc/src/main/scala/clients/ResPool.scala

## Websocket support. ( intial proof of concept, test implementation )

One of the way is to use simplified flow for websockets with function process_io().
It takes a parameter, another function: 
           io_func : WebSocketFrame => ZIO[ZEnv, Throwable, WebSocketFrame]
io_func() will be called automacticaly for each packet comming.

If incoming packet is a CONTINUATION packet and it is not a last packet returning value of io_func() will be ignored! 
This allows to accumulate data from websockets and only send a reply after all data has been read.

Control packets ( PING/PONG will be processed automaticaly ).

Usage of process_io() is optional.
Obviously basic functions on WebSocket() can be used directly.

```scala
def accept( req : Request ) : ZIO[ZEnv with MyLogging, Exception, Unit]
def acceptAndRead(req: Request): ZIO[ZEnv with MyLogging, Exception, WebSocketFrame]
def writeFrame( req : Request, frame : WebSocketFrame )
def readFrame(req: Request ) : ZIO[ZEnv, Exception, WebSocketFrame ]
```

Websocket example with process_io()

```scala
val ws_route2 = HttpRoutes.of { req: Request =>
   {
      req match {
        case GET -> Root / "websocket" =>
              if (req.isWebSocket) {
                    val session = Websocket();
                    session.accept( req ) *>
                    session.process_io( req, in => {
                    /* expect text or cont */
                          if ( in.opcode == WebSocketFrame.BINARY ) ZIO( WebSocketFrame.Close() )
                          else {
                                //types data on screen, this support CONTINUATION packets automaticaly.
                                //for each packet there will be a separate putStrLn
                                //only one "Hello From Server will be sent back, afer last CONT packet is received.
                                zio.console.putStrLn( "ABC> " + new String( in.data.toArray )) *>
                                ZIO( WebSocketFrame.Text( "Hello From Server", true ) )
                          }   
                    } )
        } else  ZIO(Response.Error(StatusCode.NotFound))
   }
} 
```    
