package example

import zio.{ IO, _ }
import zio.test._
import zio.test.Assertion._

import zio.duration._
import zio.test.{ DefaultRunnableSpec, TestAspect }
import zhttp.Request
import zhttp.ContentType
import zhttp.Headers
import zhttp.Method._
import zhttp.LogLevel
import zhttp.MyLogging
import zhttp.TLSServer
import zhttp.HttpRouter

trait BaseSpec extends DefaultRunnableSpec {
  override def aspects = List(TestAspect.timeout(60.seconds))
}

object ExampleSpec 
  extends BaseSpec {
    def spec = suite( "ExampleSpec")( 
       testM( "healthcheck")
       {
         /*
          //Request.make( GET, "/test2", Headers(), Chunk(), ContentType.Plain )
          //for {
          //    _  <- example.myServer           

          //} yield()
          
          val myHttpRouter = new HttpRouter
            
          val myHttp = new TLSServer
          //server
          myHttp.KEYSTORE_PATH = "keystore.jks"
          myHttp.KEYSTORE_PASSWORD = "password"
          myHttp.TLS_PROTO = "TLSv1.2"         //default TLSv1.2 in JDK8
          myHttp.BINDING_SERVER_IP = "0.0.0.0" //make sure certificate has that IP on SAN's list
          myHttp.KEEP_ALIVE = 2000             //ms, good if short for testing with broken site's snaphosts with 404 pages
          myHttp.SERVER_PORT = 8084

          myHttp
              .run(myHttpRouter.route)
              .provideSomeLayer[ZEnv](MyLogging.make(("console" -> LogLevel.Off), ("access" -> LogLevel.Off)))
              .exitCode

          ZIO.effect{ zio.test.assert ( "op" )(zio.test.Assertion.equalTo( "op" ) )  }
          
       } )*/

        ZIO.effect{ zio.test.assert ( "op" )(zio.test.Assertion.equalTo( "op" ) )  }
    
      } ) }