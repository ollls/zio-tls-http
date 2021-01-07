package zhttp

import MyLogging.Service
import MyLogging.MyLogging

import zio.{ ZEnv, ZIO }
import zio.Has

//normal app routes - A is Request
final case class HttpRoutes[ -MyEnv <: Has[MyLogging.Service]](
  run: Request => ZIO[ZEnv with MyEnv, Option[Exception], Response],
  postProc: HttpRoutes.PostProc
)

object HttpRoutes {

  case class WebFilterProc[ -MyEnv <: Has[MyLogging.Service]](
    run: Request => ZIO[ZEnv with MyEnv, Throwable, Response]
  ) {
    def <> [MyEnv0 <: Has[MyLogging.Service]](another: WebFilterProc[MyEnv0]) = combine[MyEnv0](another.run)

    def <> [MyEnv0 <: Has[MyLogging.Service]](another: Request => ZIO[ZEnv with MyEnv0, Throwable, Response]) =
      combine[MyEnv0](another)

    ////////////////////////////////////////////////////////////////////////////////////////vb
    def combine[MyEnv0 <: Has[MyLogging.Service]](
      another: Request => ZIO[ZEnv with MyEnv0, Throwable, Response]
    ): WebFilterProc[MyEnv with MyEnv0] = {
      def new_proc(req: Request): ZIO[ZEnv with MyEnv with MyEnv0, Throwable, Response] =
        run(req).flatMap(
          resp0 => {
            //transfer custom headers from filter1 response to filter2 request
            //we can chain on custom headers
            val req2 = Request(req.headers ++ resp0.headers, req.body, req.ch)
            if (resp0.code.isSuccess) another(req2).map(resp2 => resp2.hdr(resp0.headers))
            else ZIO.succeed(resp0)
          }
        )
      WebFilterProc[MyEnv with MyEnv0](new_proc)
    }

  }

  type PostProc = Response => Response
  //type WebFilterProc = Request => ZIO[ZEnv, Throwable, Response

  //replaces sequence and OptionT
  def OptionToOptionalZIOError[A, MyEnv <: Has[MyLogging.Service]](
    oza: Option[ZIO[ZEnv with MyEnv, Throwable, A]]
  ): ZIO[ZEnv with MyEnv, Option[Exception], A] =
    oza match {
      case Some(x) => x.refineToOrDie[Exception].asSomeError
      case None    => ZIO.fromOption(None)
    }

  // def __filter[ MyEnv <: Has[MyLogging.Service ] ( WebFilterProc[MyEnv]) =

  private var _filter: WebFilterProc[MyLogging] = WebFilterProc((_) => ZIO(Response.Ok))
  private var _postProc: PostProc               = (r: Response) => r

  def defaultFilter                                                 = _filter.run
  def defaultFilter(ft0: Request => ZIO[ZEnv, Throwable, Response]) = _filter = WebFilterProc(ft0)

  def defaultPostProc                 = _postProc
  def defaultPostProc(proc: PostProc) = _postProc = proc

  def of[MyEnv <: Has[MyLogging.Service]](pf: PartialFunction[Request, ZIO[ZEnv with MyEnv, Throwable, Response]]) =
    ofWithFilter[MyEnv](_filter, _postProc)(pf)

  def ofWithPostProc[MyEnv <: Has[MyLogging.Service]](
    postProc: PostProc
  )(pf: PartialFunction[Request, ZIO[ZEnv with MyEnv, Throwable, Response]]) =
    ofWithFilter[MyEnv](_filter, postProc)(pf)

  def ofWithFilter[MyEnv <: Has[MyLogging.Service]](
    filter0: WebFilterProc[MyEnv],
    postProc0: PostProc = _postProc
  )(pf: PartialFunction[Request, ZIO[ZEnv with MyEnv, Throwable, Response]]): HttpRoutes[MyEnv] = {

    //preceded with default filter first
    val filter = if (filter0 != _filter) _filter <> filter0 else filter0

    // default post proc called last, defaultPostProc ( mypostProc( response )
    val postProc = if (postProc0 != _postProc) _postProc.compose(postProc0) else postProc0

    //filter partial function - will call filter only if second route function defined
    val f0: PartialFunction[Request, ZIO[ZEnv with MyEnv, Throwable, Response]] =
      (req: Request) => {
        req match {
          case (req: Request) if pf.isDefinedAt(req) => filter.run(req)
        }
      }

    //resulting lifted and sequenced function, which combines filter and route processing
    def res0(req: Request): ZIO[ZEnv with MyEnv, Option[Exception], Response] =
      for {
        filter_resp <- OptionToOptionalZIOError[Response, MyEnv](f0.lift(req))

        new_req <- ZIO.effectTotal(Request(req.headers ++ filter_resp.headers, req.body, req.ch))

        route_resp <- if (filter_resp.code.isSuccess) OptionToOptionalZIOError[Response, MyEnv](pf.lift(new_req))
                     else ZIO.succeed(filter_resp)

      } yield (route_resp)

    HttpRoutes[MyEnv](res0, postProc)
  }
}
