# Lightweight Scala TLS HTTP 1.1 Web Server based on ZIO async fibers and Java NIO sockets.


![alt text](https://github.com/ollls/zio-tls-http/blob/main/Screenshot.jpg?raw=true)

## How to run.
Please, use docker image to run it or use sbt run.  
To run in docker:

    sbt package
    docker build --pull --rm -f "Dockerfile" -t ziohttp:latest "."
    docker container run --name zhttp_container -p 443:8084 ziohttp:latest

    https://localhost        ZIO doc static web server example
    https://localhost/print  print headers example
    https://localhost/test   JSON output example
    
    Use case examples:
    https://github.com/ollls/zio-tls-http/blob/main/src/main/scala/MyServer.scala


    
Server will use self-signed SSL certificate, you will need to configure the browser to trust it.
Certificate resides in keystore.jks


## Approach. 
Bottom to Top design, always rely on standard Java library whenever possible. 
The goal is to provide small and simple HTTP JSON server with all the benefits of async monadic non-blocking JAVA NIO calls wrapped up into ZIO interpreter. 

## Overview.
Web Server has it's own implementation of TLS protocol layer based on JAVA NIO and standard JDK SSLEngine. Everything is modeled as ZIO effects and processed as async routines with Java NIO. Java NIO and Application ZIO space uses same thread pool for non-blocking operations.
Server implements a DSL for route matching, it's very similar ( but a bit simplified ) to the one which is used in HTTP4s. Server implements pluggable pre-filters and post-filters.
Server has two types of application routes, so called: channel routes and app routes. Channel routes allows to write a code without fetching complete body into memory and Application routes provide more convenient interface for basic JSON work where Response and Request body is read in fetched as ZIO Chunk. It has a reference implementation of basic static HTTP file server and was extensively tested as such. Currently for file operations examples we don't use nio calls and we don't use Managed or any other resource bracketing, this will be added later.

## State of the project. ( testing, performance, etc )
Performance tests are under way, but expectation is that on core i9 machine, simple JSON encoding GET call can be done in up to 20 000 TPS. 



## Logs:  Logs doesn't support log rotation at thus moment.
Logs implemented with ZIO enironment and ZQueue. Currently there is only two logs: access and console.

You can specify desired loglevel on server initialization.
By default log with name "console" will print color data on screen.
Also, "access" log will duplicate output to console if console LogLevel.Trace.
To avoid too many messages being posted to console, just increase "console" LogLevel.

    myHttp
      .run(myHttpRouter.route)
      .provideSomeLayer[ZEnv](MyLogging.make(("console" -> LogLevel.Trace), ("access" -> LogLevel.Info)))
      .exitCode
  }

## Media encoding.

Http Response contains methods:

    def asTextBody(body0: String) : Response
    def asJsonBody[B : JsonValueCodec]( body0 : B ) : Response
  

## Route matching DSL by examples.

#### Basic example

- Simple route returning http code Ok with text body.

      val appRoute1 = HttpRoutes.of {
            case GET -> Root / "hello" => ZIO(Response.Ok.asTextBody("Hello World"))
      }
      
      
- Simple JSON response      
      
      val another_app = HttpRoutes.ofWithFilter(proc1) { req =>
            req match {
                case GET -> Root / "test" =>
                    ZIO(Response.Ok.asJsonBody( DataBlock("Thomas", "1001 Dublin Blvd", 
                                               Array( "Red", "Green", "Blue"))) )
            }   
        }
  
- How to read from JSON represented by case class  
      
      case POST -> Root / "test" => 
         ZIO.effect { //need to wrap up everything in the effect to have proper error handling
           val db : DataBlock = req.fromJSON[DataBlock]
           val name = db.name
           Response.Ok.asTextBody( s"JSON for $name accepted" )     
         }                                  
      }   

- Example with cookies, path and variable parameters.

  *Please, note a raw param string is always available with req.uri.getQuery*

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
      
- How to post a file with AppRoute ( file will be pre-read in memory for AppRoute integration )

            ...
      case POST -> Root / "receiver" / StringVar(fileName) =>
            effectBlocking {
              val infile = new java.io.FileOutputStream( ROOT_CATALOG + "/" + fileName)
              infile.write(req.body.toArray)
              infile.close()
            }.refineToOrDie[Exception] *> ZIO(Response.Ok)


## Filters and composition of filters.

 Web filter is a simple function:  Response => ZIO( Request ). Inside of the web filter a decision can be made whether to allow access to resource or return HTTP error code.
 If you chain several filters with "<>" chain will be interrupted once a non 2xx code will be returned by at least one of the filters in the chain.
 
 Defining two web filters, they will be called before any user defined app route logic.

      val proc1 = WebFilterProc( (_) => ZIO(Response.Ok.hdr("Injected-Header-Value" -> "1234").hdr("Injected-Header-Value" -> "more" ) ) )

      val proc2 = WebFilterProc( req  => ZIO {
                       if ( req.headers.getMval( "Injected-Header-Value").exists( _ == "1234" ) )
                       Response.Ok.hdr("Injected-Header-Value" -> "CheckPassed") 
                       else Response.Error( StatusCode.Forbidden ) 
                              } )
 
 Here we combine proc1 and proc2 together.
 
      val proc3 = proc1 <> proc2 
 
 Filters can be assigned per each app route, exactly same way as we did with HttpRoutes.of() but with ofWithFilter(). 
 Appropriate filter will be called only if route matches, there is a special logic which build a final route function out of a filter and user defined app route partial  function. 
 
 Example:
 
      val another_app = HttpRoutes.ofWithFilter(proc3) {
            case GET -> Root / "test" =>
        ZIO(Response.Ok.contentType(ContentType.JSON).body(DataBlock("Name", "Address")))
      }


## Post filters.
Post filters are different from pre-filters described earlier. 
The goal of Post-Filter is to provide extra data in the form of http headers in the user output. ( such as CORS headers ).
Currently post filter is a simple:

      type PostProc      = Response[_] => Response[_]
      
      val openCORS: HttpRoutes.PostProc = (r) => r.hdr(("Access-Control-Allow-Origin" -> "*"))
                                            .hdr(("Access-Control-Allow-Method" -> "POST, GET, PUT"))
                                            
Post filters are used same way:

       val another_app2 = HttpRoutes.ofWithFilter(proc3, openCORS) { req =>
          req match {
             case GET -> Root / "test2" =>
               ZIO(Response.Ok.contentType(ContentType.Plain).body(req.headers.printHeaders))
           }
         }
         
  or
  
      val another_app2 = HttpRoutes.ofWithPostProc( openCORS) { req =>
        req match {
          case GET -> Root / "test2" =>
           ZIO(Response.Ok.contentType(ContentType.Plain).body(req.headers.printHeaders))
        }
       }
         

## Channel routes and example of static web server.

Channel routes do nothing but provide raw "ch" : channel in response, so user is responsible for reading and processing data blocks as they come.
There a simple static Web Server implemented based on that concept. It was used for access to ZIO documentation and tested with complex snapshots of several web sites.
Channel is available from Request::ch, with two simple functions:

       def read: ZIO[ZEnv, Exception, Chunk[Byte]]
       def write(chunk: Chunk[Byte]): ZIO[ZEnv, Exception, Int]
  

Here is a static web server example with channel routes. It serves 3 catalogs with different documents. It accepts file uploads to "/save" without preloading them into memory.
Please, note matching operator "/:" - which means all the subfolders under provided folder.
For GET requests we are not interested in getting data by chunks, so we complete get requests with service function finishBodyLoadRequests() called explicitly.


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

      def accept( req : Request ) : ZIO[ZEnv with MyLogging, Exception, Unit]
      def acceptAndRead(req: Request): ZIO[ZEnv with MyLogging, Exception, WebSocketFrame]
      def writeFrame( req : Request, frame : WebSocketFrame )
      def readFrame(req: Request ) : ZIO[ZEnv, Exception, WebSocketFrame ]

Websocket example with process_io()

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






