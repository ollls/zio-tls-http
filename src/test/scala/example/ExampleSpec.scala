package example

import zio.ZIO
import zio.test._
//import zio.test.Assertion._

import zio.duration._
import zio.test.{ DefaultRunnableSpec, TestAspect }


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