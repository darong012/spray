/*
 * Copyright (C) 2011-2012 spray.cc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.site

import akka.actor.Actor
import cc.spray.httpx.encoding.Gzip
import cc.spray.httpx.TwirlSupport._
import cc.spray.http.StatusCodes
import cc.spray.routing._
import html._


class SiteServiceActor extends Actor with HttpService {
  def actorRefFactory = context

  def receive = runRoute {
    dynamic { // for proper support of twirl + sbt-revolver during development, can be removed in production
      (get & encodeResponse(Gzip)) {
        path("") {
          redirect("/home")
        } ~
        path("home") {
          complete(page(home()))
        } ~
        path("index") {
          complete(page(index()))
        } ~
        getFromResourceDirectory {
          "theme"
        } ~
        pathPrefix("_images") {
          getFromResourceDirectory("sphinx/json/_images")
        } ~
        path(Rest) { docPath =>
          rejectEmptyResponse {
            complete(render(docPath))
          }
        } ~
        complete(StatusCodes.NotFound, page(error404())) // fallback response is 404
      }
    }
  }

  def render(docPath: String) =
    RootNode.find(docPath) map { node =>
      SphinxDoc.load(node.uri).orElse(SphinxDoc.load(node.uri + "index")) match {
        case Some(SphinxDoc(body)) => page(sphinxDoc(node, body), node)
        case None => throw new RuntimeException("SphinxDoc for uri '%s' not found" format node.uri)
      }
    }

}