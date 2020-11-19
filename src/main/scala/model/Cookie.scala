/*
 *
 *  Copyright 2017-2019 John A. De Goes and the ZIO Contributors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package zhttp

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object Cookie {

   def toString( cookie : Cookie ) = cookie.toString

}

// https://tools.ietf.org/html/rfc6265
final case class Cookie(
  name:  String,
  value: String,
  domain: Option[String] = None,
  path: Option[String] = None,
  expires: Option[ZonedDateTime] = None,
  maxAge: Option[Long] = None,
  secure: Boolean   = false,
  httpOnly: Boolean = false,
  sameSite : Option[String] = None ) 
  {
    override def toString = 
    {      
     val ls = List[String]();

     val attributes = 
     ( path match {
       case Some(value) => s"Path=$value" :: ls 
       case None => ls
     } ) ++ ( domain match {
       case Some(value) => s"Domain=$value" :: ls 
       case None => ls 
     } ) ++ ( maxAge match {
       case Some(value) => s"Max-Age=$value" :: ls
       case None => ls
     }) ++ (
        expires match {
           case Some( value ) => { 
              val UTCstr =  value.format( DateTimeFormatter.RFC_1123_DATE_TIME )
              s"Expires=$UTCstr" :: ls 
           }
           case None => ls
        }
     ) ++
      ( if( secure ) "Secure" :: ls else ls 
      ) ++ ( if( httpOnly ) "HttpOnly" :: ls else ls 
      ) ++ ( sameSite match {
         case Some(value) => s"SameSite=$value" :: ls
         case None => ls   
      })

     val cookieAttributes = attributes.mkString( "; " )

     s"$name=$value; $cookieAttributes" 

  } 
}
