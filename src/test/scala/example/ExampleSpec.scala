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
        ZIO.effect{ zio.test.assert ( "op" )(zio.test.Assertion.equalTo( "op" ) )  }
    
      } ) }