package zhttp

import zio.{ ZIO }

final case class HttpRoutes[-Env](
  run: Request => ZIO[Env, Option[Exception], Response],
  postProc: HttpRoutes.PostProc
)

object HttpRoutes {

  case class WebFilterProc[-Env](
    run: Request => ZIO[Env, Throwable, Response]
  ) 
  {
    def <>[E <: Env] (another: WebFilterProc[E]) = combine[E](another.run)

    def <>[E <: Env] (another: Request => ZIO[E, Throwable, Response]) = combine[E](another)

    ////////////////////////////////////////////////////////////////////////////////////////vb
    def combine[E <: Env](
      another: Request => ZIO[E, Throwable, Response]
    ): WebFilterProc[E] = {
      def new_proc(req: Request): ZIO[E, Throwable, Response] =
        run(req).flatMap(
          resp0 => {
            //transfer custom headers from filter1 response to filter2 request
            //we can chain on custom headers
            val req2 = Request(req.headers ++ resp0.headers, req.stream, req.ch)
            if (resp0.code.isSuccess) another(req2).map(resp2 => resp2.hdr(resp0.headers))
            else ZIO.succeed(resp0)
          }
        )
      WebFilterProc[E](new_proc)
    }

  }

  type PostProc = Response => Response
  //type WebFilterProc = Request => ZIO[ZEnv, Throwable, Response

  //replaces sequence and OptionT
  def OptionToOptionalZIOError[A, Env](
    oza: Option[ZIO[Env, Throwable, A]]
  ): ZIO[Env, Option[Exception], A] =
    oza match {
      case Some(x) => x.refineToOrDie[Exception].asSomeError
      case None    => ZIO.fromOption(None)
    }

  private var _filter             = WebFilterProc((_) => ZIO.succeed(Response.Ok()))
  private var _postProc: PostProc = (r: Response) => r

  //default flter ony with MyLogging, supporting all envs will incur serious chnages.
  //not sure how to share a variable with type parameters, can use only predef MyLogging
  def defaultFilter(ft0: Request => ZIO[Any, Throwable, Response]) =
    _filter = WebFilterProc(ft0)

  def defaultPostProc                 = _postProc
  def defaultPostProc(proc: PostProc) = _postProc = proc

  def of[Env](pf: PartialFunction[Request, ZIO[Env, Throwable, Response]]) =
    ofWithFilter(_filter, _postProc)(pf)

  def ofWithPostProc[Env](
    postProc: PostProc
  )(pf: PartialFunction[Request, ZIO[ Env, Throwable, Response]]) =
    ofWithFilter[Env](_filter, postProc)(pf)

  def ofWithFilter[Env](
    filter0: WebFilterProc[Env],
    postProc0: PostProc = _postProc
  )(pf: PartialFunction[Request, ZIO[ Env, Throwable, Response]]): HttpRoutes[Env] = {

    //preceded with default filter first
    val filter = if (filter0 != _filter) _filter.combine(filter0.run) else filter0

    // default post proc called last, defaultPostProc ( mypostProc( response )
    val postProc = if (postProc0 != _postProc) _postProc.compose(postProc0) else postProc0

    //filter partial function - will call filter only if second route function defined
    val f0: PartialFunction[Request, ZIO[ Env, Throwable, Response]] =
      (req: Request) => {
        req match {
          case (req: Request) if pf.isDefinedAt(req) => filter.run(req)
        }
      }

    //resulting lifted and sequenced function, which combines filter and route processing
    def res0(req: Request): ZIO[ Env, Option[Exception], Response] =
      for {
        filter_resp <- OptionToOptionalZIOError[Response, Env](f0.lift(req))

        new_req <- ZIO.succeed(Request(req.headers ++ filter_resp.headers, req.stream, req.ch))

        route_resp <- if (filter_resp.code.isSuccess) OptionToOptionalZIOError[Response, Env](pf.lift(new_req))
                     else ZIO.succeed(filter_resp)

      } yield (route_resp)

    HttpRoutes[Env](res0, postProc)
  }
}
