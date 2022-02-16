package example

import zio.ZIO
import zio.test._
//import zio.test.Assertion._


import zio.test.TestAspect
import zio._
import zio.test.ZIOSpecDefault


trait BaseSpec extends ZIOSpecDefault {
 // override def aspects = List(TestAspect.timeout(60.seconds))
}

object ExampleSpec 
  extends BaseSpec {
    def spec = suite( "ExampleSpec")( 
       test( "healthcheck")
       {
        ZIO.attempt{ zio.test.assert ( "op" )(zio.test.Assertion.equalTo( "op" ) )  }
    
      } ) }