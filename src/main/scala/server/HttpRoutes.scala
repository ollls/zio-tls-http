package zhttp

import zio.{ ZEnv, ZIO }
import MyLogging.MyLogging

//normal app routes - A is Request
final case class HttpRoutes[A](run: Request => ZIO[ZEnv with MyLogging, Option[Exception], A], postProc: HttpRoutes.PostProc)

object HttpRoutes {

  case class WebFilterProc( run : Request => ZIO[ZEnv, Throwable, Response] )
  {
      def <>( another : WebFilterProc ) = combine( another.run ) 
      def <>( another : Request => ZIO[ZEnv, Throwable, Response] ) = combine( another )  

      ////////////////////////////////////////////////////////////////////////////////////////vb 
      def combine( another : Request => ZIO[ZEnv, Throwable, Response] ) : WebFilterProc =
      {
          def new_proc( req : Request ) : ZIO[ZEnv, Throwable, Response] = 
          {
             run( req ).flatMap( 
                        resp0 => { 
                                    //transfer custom headers from filter1 response to filter2 request
                                    //we can chain on custom headers
                                    val req2 = Request( req.headers ++ resp0.headers, req.body, req.ch )  
                                    if ( resp0.code.isSuccess ) another( req2 ).map( resp2 => resp2.hdr( resp0.headers )) 
                                    else ZIO.succeed(resp0)
                                 }   
                        )
          }
          WebFilterProc( new_proc )
      }

  }
  
  type PostProc      = Response => Response
  //type WebFilterProc = Request => ZIO[ZEnv, Throwable, Response

  //replaces sequence and OptionT
  def OptionToOptionalZIOError[A](oza: Option[ZIO[ZEnv with MyLogging, Throwable, A]]): ZIO[ZEnv with MyLogging, Option[Exception], A] =
    oza match {
      case Some(x) => x.refineToOrDie[Exception].asSomeError
      case None    => ZIO.fromOption(None)
    }

  private var _filter: WebFilterProc = WebFilterProc( (_) => ZIO(Response.Ok) )
  private var _postProc: PostProc    = (r: Response) => r

  def defaultFilter = _filter.run
  def defaultFilter(ft0: Request => ZIO[ZEnv, Throwable, Response]) = _filter = WebFilterProc( ft0 )

  def defaultPostProc                 = _postProc
  def defaultPostProc(proc: PostProc) = _postProc = proc

  def of(pf: PartialFunction[Request, ZIO[ZEnv with MyLogging, Throwable, Response]]) =
    ofWithFilter(_filter, _postProc)(pf)

  def ofWithPostProc(postProc: PostProc)(pf: PartialFunction[Request, ZIO[ZEnv with MyLogging, Throwable, Response]]) =
    ofWithFilter(_filter, postProc)(pf)

  def ofWithFilter(
    filter0: WebFilterProc,
    postProc0: PostProc = _postProc
  )(pf: PartialFunction[Request, ZIO[ZEnv with MyLogging, Throwable, Response]]): HttpRoutes[Response] = {

    //preceded with default filter first
    val filter   = if ( filter0   != _filter )  _filter <> filter0  else filter0

    // default post proc called last, defaultPostProc ( mypostProc( response )
    val postProc = if ( postProc0 != _postProc ) _postProc compose postProc0 else postProc0

    //filter partial function - will call filter only if second route function defined
    val f0: PartialFunction[Request, ZIO[ZEnv, Throwable, Response]] =
      (req: Request) => {
        req match {
          case (req: Request) if pf.isDefinedAt(req) => filter.run(req)
        }
      }

    //resulting lifted and sequenced function, which combines filter and route processing
    def res0(req: Request): ZIO[ZEnv with MyLogging, Option[Exception], Response] =
      for {
        filter_resp <- OptionToOptionalZIOError(f0.lift(req))

        new_req <- ZIO.effectTotal(Request(req.headers ++ filter_resp.headers, req.body, req.ch))

        route_resp <- if (filter_resp.code.isSuccess) OptionToOptionalZIOError(pf.lift(new_req))
                     else ZIO.succeed(filter_resp)

      } yield (route_resp)

    HttpRoutes(res0, postProc)
  }
}
