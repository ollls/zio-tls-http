package example

import zhttp.TLSServer
import zhttp.clients._
import zhttp.HttpRoutes
import zhttp.dsl._
import zhttp.MyLogging._
import zhttp.MyLogging
import zhttp.LogLevel
import zhttp.HttpRouter
import zhttp.StatusCode
import zhttp.clients.ResPool.ResPool

import zhttp.Response
import zhttp.Method._

import zio.App
import zio.ZIO
import zio.ZEnv
import zio.Chunk
import zio.json._

import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.SearchResultEntry
import clients.ResPoolCache
import clients.ResPoolCache.ResPoolCache

object ServerExample extends zio.App {

  def NotNull(s: String): String = if (s == null) "" else s

  val ATTRIBUTES = Seq(
    "uid",
    "employeetype",
    "cn",
    "customPreferredLastName",
    "customnameprefix",
    "customPreferredFirstName",
    "mobilenumber",
    "customPreferredMiddleName",
    "mail",
    "telephonenumber",
    "displayname"
  )

  object UserInfo2 {

    def apply(e: SearchResultEntry) = {
      val login           = NotNull(e.getAttributeValue("uid"))
      val displayName     = NotNull(e.getAttributeValue("displayname"))
      val namePrefix      = NotNull(e.getAttributeValue("customnameprefix"))
      val givenname       = NotNull(e.getAttributeValue("customPreferredLastName"))
      val surname         = NotNull(e.getAttributeValue("customPreferredLastName"))
      val middleName      = NotNull(e.getAttributeValue("customPreferredMiddleName"))
      val mobile          = NotNull(e.getAttributeValue("custommobilenumber"))
      val telephoneNumber = NotNull(e.getAttributeValue("telephonenumber"))
      val email           = NotNull(e.getAttributeValue("mail"))
      new UserInfo2(login, displayName, namePrefix, givenname, surname, middleName, mobile, telephoneNumber, email)
    }

    implicit val decoder: JsonDecoder[UserInfo2] = DeriveJsonDecoder.gen[UserInfo2]
    implicit val encoder: JsonEncoder[UserInfo2] = DeriveJsonEncoder.gen[UserInfo2]
  }

  case class UserInfo2(
    val login: String,
    val displayName: String,
    val namePrefix: String,
    val givenname: String,
    val surname: String,
    val middleName: String,
    val mobile: String,
    val telephoneNumber: String,
    val email: String
  )

  def run(args: List[String]) = {

    val edg_ext_users_route = HttpRoutes.of {

      case GET -> Root / "service2" / "users" / StringVar(uid) =>
        for {
          res <- ResPoolCache.get[String, Chunk[SearchResultEntry], LDAPConnection](uid)
        } yield (res.headOption match {
          case Some(entry) => Response.Ok.asJsonBody(UserInfo2(entry))
          case None        => Response.Error(StatusCode.NotFound)
        })

      case GET -> Root / "service" / "users" / StringVar(uid) =>
        for {
          con <- ResPool.acquire[LDAPConnection]
          res <- AsyncLDAP.a_search(con, "o=company.com", s"uid=$uid", ATTRIBUTES: _*)
          _   <- ResPool.release[LDAPConnection](con)

        } yield (res.headOption match {
          case Some(entry) => Response.Ok.asJsonBody(UserInfo2(entry))
          case None        => Response.Error(StatusCode.NotFound)
        })

      case GET -> Root / "service" / "health" =>
        ZIO(Response.Ok.asTextBody("Health Check Ok"))

    }

    type MyEnv = MyLogging
      with ResPool[LDAPConnection]
      with ResPoolCache[String, Chunk[SearchResultEntry], LDAPConnection]

    val edgz_Http    = new TLSServer[MyEnv]
    val myHttpRouter = new HttpRouter[MyEnv]

    myHttpRouter.addAppRoute(edg_ext_users_route)

    edgz_Http.KEYSTORE_PATH = "keystore.jks"
    edgz_Http.KEYSTORE_PASSWORD = "password"

    edgz_Http.TLS_PROTO = "TLSv1.2"
    edgz_Http.BINDING_SERVER_IP = "0.0.0.0"
    edgz_Http.KEEP_ALIVE = 2000
    edgz_Http.SERVER_PORT = 8443

    AsyncLDAP.HOST = "localhost"
    AsyncLDAP.PORT = 636
    AsyncLDAP.BIND_DN = "uid=your_dn,o=company.com"
    AsyncLDAP.PWD = "yourpassword"

    //Layers
    val logger_L = MyLogging.make(("console" -> LogLevel.Trace), ("access" -> LogLevel.Info))

    val ldap_L =
      ResPool.make[LDAPConnection](timeToLiveMs = 20 * 1000, AsyncLDAP.ldap_con_ssl, AsyncLDAP.ldap_con_close)

    val cache_L = ResPoolCache.make(
      timeToLiveMs = 7 * 1000,
      (c: LDAPConnection, uid: String) => AsyncLDAP.a_search(c, "o=company.com", s"uid=$uid", ATTRIBUTES: _*)
    )

    //another way working with mem cache layer
    //direct access to conn pool hidden
    //  val cached_ldap_L = ldap_L >>> cache
    //  edgz_Http
    //  .run(myHttpRouter.route)
    //  .provideSomeLayer[ZEnv with MyLogging]( cached_ldap_L )
    //  .provideSomeLayer[ZEnv](logger_L)
    //  .exitCode

    //all layers visibe
    edgz_Http
      .run(myHttpRouter.route)
      .provideSomeLayer[ZEnv with MyLogging with ResPool[LDAPConnection]](cache_L)
      .provideSomeLayer[ZEnv with MyLogging](ldap_L)
      .provideSomeLayer[ZEnv](logger_L)
      .exitCode
  }

}
