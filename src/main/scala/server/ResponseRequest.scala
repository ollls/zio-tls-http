package zhttp

import zio.Chunk
import java.net.URI
import zio.json._


sealed case class Request(headers: Headers, body: Chunk[Byte], ch: Channel) {
  def path: String             = headers.get( HttpRouter._PATH ).getOrElse("")
  def method: Method           = Method(headers.get(HttpRouter._METHOD).getOrElse(""))
  def contentLen: String       = headers.get("content-length").getOrElse("0") //keep it string
  def uri: URI                 = new URI(path)
  def contentType: ContentType = ContentType(headers.get("content-type").getOrElse(""))
  def isJSONBody: Boolean      = contentType == ContentType.JSON
  def isWebSocket: Boolean     = headers.get("Upgrade").map( _.equalsIgnoreCase( "websocket") )
                                .collect{ case true => true }.getOrElse( false )

  def fromJSON[A : JsonDecoder] : A = {
     new String(body.toArray).fromJson[A] match {
       case Right(v) => v
       case Left(v)  => throw new MediaEncodingError( s"JSON schema error: $v" )
     }

     
  }                              
                     
}



object Response {

  def Ok(): Response = new Response(StatusCode.OK, Headers(), None)

  def Error(code: StatusCode): Response = new Response(code, Headers(), None)
}


object NoResponse extends Response(StatusCode.NotImplemented, null, None)

//Response ///////////////////////////////////////////////////////////////////////////
sealed case class Response(code: StatusCode, headers: Headers, body: Option[Chunk[Byte]] ) {
   
  def hdr(hdr: Headers): Response = new Response(this.code, this.headers ++ hdr, this.body)

  def hdr(pair: (String, String))    = new Response(this.code, this.headers + pair, this.body)
  
  def cookie( cookie : Cookie ) = { 
      val pair = ( "Set-Cookie" -> cookie.toString() )
      new Response(this.code, this.headers + pair, this.body)
  }     

  def asTextBody(body0: String): Response  = {
     new Response(this.code, this.headers, Some( Chunk.fromArray(body0.getBytes ) ) ).contentType( ContentType.Plain )
  }   

  def asJsonBody[B : JsonEncoder]( body0 : B ) : Response = { 
      val json = body0.toJson.getBytes
      new Response(this.code, this.headers, Some( Chunk.fromArray( json ))).contentType( ContentType.JSON) 
  }    

  //def empty[None] : Response[None] = new Response[None](this.code, this.headers, None )

  def contentType(type0: ContentType) =
    new Response(this.code, this.headers + ("content-type" -> type0.toString()), this.body)
}



