/*
 *
 *  Copyright 2017-2020 John A. De Goes and the ZIO Contributors
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

final case class ContentType(value: String) extends AnyVal {
  override def toString: String = value
}

object ContentType {
  val Plain       = ContentType("text/plain")
  val HTML        = ContentType("text/html")
  val CSV         = ContentType("text/csv")
  val XML         = ContentType("text/xml")
  val JSON        = ContentType("application/json")
  val OctetStream = ContentType("application/octet-stream")
  val Form        = ContentType("application/x-www-form-urlencoded")
  val Image_JPEG  = ContentType("image/jpeg")
  val Image_GIF   = ContentType("image/gif")
  val Image_PNG   = ContentType("image/png")
  val CSS         = ContentType("text/css")
  val JavaScript  = ContentType("application/javascript") 
}