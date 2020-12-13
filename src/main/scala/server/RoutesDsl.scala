package zhttp.dsl

import zhttp._
import scala.util.Try
import java.net.URI

trait Path {
  def / (child: String) = new /(this, child)
  def toList: List[String]
}

case object Root extends Path {
  def toList: List[String] = Nil

  override def toString = ""
}

final case class /(parent: Path, child: String) extends Path {
  lazy val toList: List[String] = parent.toList ++ List(child)

  lazy val asString: String = s"$parent/$child"

  override def toString: String = asString
}

object Path {

  def apply(path: String): Path = {
    val segs: Array[String] = path.split("/")

    var res: Path = null;

    segs.foreach { seg =>
      {
        if (seg.isEmpty) res = Root
        else res = res / seg
      }
    }

    if (segs.isEmpty) res = Root

    res
  }

  def apply(list: List[String]): Path =
    list.foldLeft(Root: Path)(_ / _)

}

object -> {

  def unapply(req: Request): Option[(Method, Path)] =
    for {
      method <- req.headers.get( HttpRouter._METHOD)

      path <- req.headers.get( HttpRouter._PATH)

      path_u <- Some(Path(new URI(path).toString)) //.getPath) )

    } yield (( Method(method), path_u))
}

object /: {

  def unapply(path: Path): Option[(String, Path)] =
    path.toList match {
      case head :: tail => { Some(head -> Path(tail)) }
      case Nil          => None
    }
}

/* opens up matching from Root */
object /^ {

  def unapply(path: Path): Option[(Root.type, Path)] =
    Some(Root -> path)

}

protected class PathVar[A](cast: String => Try[A]) {

  def unapply(str: String): Option[A] =
    if (!str.isEmpty)
      cast(str).toOption
    else
      None
}

/**
 * Integer extractor of a path variable:
 * {{{
 *   Path("/user/123") match {
 *      case Root / "user" / IntVar(userId) => ...
 * }}}
 */
object IntVar extends PathVar(str => Try(str.toInt))

/**
 * Long extractor of a path variable:
 * {{{
 *   Path("/user/123") match {
 *      case Root / "user" / LongVar(userId) => ...
 * }}}
 */
object LongVar extends PathVar(str => Try(str.toLong))

/**
 * UUID extractor of a path variable:
 * {{{
 *   Path("/user/13251d88-7a73-4fcf-b935-54dfae9f023e") match {
 *      case Root / "user" / UUIDVar(userId) => ...
 * }}}
 */
object UUIDVar extends PathVar(str => Try(java.util.UUID.fromString(str)))

/**
 * UUID extractor of a path variable:
 * {{{
 *   Path("/user/thomasd") match {
 *      case Root / "user" / StringVar(userId) => ...
 * }}}
 */
object StringVar extends PathVar(str => Try(str))

object :? {

  def unapply(req: Request): Some[(Request, Map[String, String])] = {
    val q1 = req.uri.getQuery
    val q  = if (q1 != null) q1 else ""

    val map = q
      .split("&")
      .foldLeft(Map.empty[String, String])((z, v) => {
        val pair = v.trim.split("=");
        if (pair.length == 2)
          z + (pair(0).trim -> pair(1).trim)
        else if (pair.length == 1)
          z + (pair(0).trim -> "")
        else
          z
      })

    Some((req, map))
  }
}

class QueryParam(name: String) {

  def unapply(params: Map[String, String]): Option[String] =
    Some(params.get(name).getOrElse(""))

}
