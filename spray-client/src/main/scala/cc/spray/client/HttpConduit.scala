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

package cc.spray.client

import akka.dispatch.{Promise, Future}
import akka.spray.{RefUtils, UnregisteredActorRef}
import akka.actor._
import cc.spray.can.client.HttpClient
import cc.spray.httpx.{ResponseTransformation, RequestBuilding}
import cc.spray.http._
import cc.spray.util._
import cc.spray.io._


class HttpConduit(val httpClient: ActorRef,
                  val host: String,
                  val port: Int = 80,
                  dispatchStrategy: DispatchStrategy = DispatchStrategies.NonPipelined(),
                  settings: ConduitSettings = ConduitSettings())
  extends Actor with ActorLogging with ConnComponent {

  val conns = Vector.tabulate(settings.MaxConnections)(i => new Conn(i + 1))
  context.watch(httpClient)

  override def postStop() {
    conns.foreach(_.close())
    context.unwatch(httpClient)
  }

  def receive = {
    case x: HttpRequest =>
      dispatchStrategy.dispatch(RequestContext(x, settings.MaxRetries, sender), conns)

    case Reply(response: HttpResponse, (conn: Conn, ctx: RequestContext, _)) =>
      conn.deliverResponse(ctx.request, response, ctx.sender)
      dispatchStrategy.onStateChange(conns)

    case Reply(_: HttpClient.SentOk, _) =>
      // ignore

    case Reply(problem, (conn: Conn, ctx: RequestContext, handle: Handle)) =>
      val error = problem match {
        case Status.Failure(e) => e
        case HttpClient.Closed(_, reason) => new RuntimeException("Connection closed, reason: " + reason)
      }
      if (!ctx.request.canBeRetried) conn.deliverError(ctx, error)
      else conn.retry(ctx, handle, error).foreach(dispatchStrategy.dispatch(_, conns))
      dispatchStrategy.onStateChange(conns)

    case Reply(HttpClient.Connected(handle), conn: Conn) =>
      conn.connected(handle)

    case Reply(Status.Failure(error), conn: Conn) =>
      conn.connectFailed(error)
      dispatchStrategy.onStateChange(conns)

    case Reply(HttpClient.Closed(handle, reason), conn: Conn) =>
      conn.closed(handle, reason)
      dispatchStrategy.onStateChange(conns)

    case Terminated(client) if client == httpClient =>
      context.stop(self)
  }
}

object HttpConduit extends RequestBuilding with ResponseTransformation {

  def sendReceive(httpConduitRef: ActorRef): HttpRequest => Future[HttpResponse] = {
    val provider = RefUtils.provider(httpConduitRef)
    request => {
      val promise = Promise[HttpResponse]()(provider.dispatcher)
      val receiver = new UnregisteredActorRef(provider) {
        def handle(message: Any)(implicit sender: ActorRef) {
          message match {
            case x: HttpResponse => promise.success(x)
            case Status.Failure(error) => promise.failure(error)
          }
        }
      }
      httpConduitRef.tell(request, receiver)
      promise
    }
  }
}

case class RequestContext(request: HttpRequest, retriesLeft: Int, sender: ActorRef) extends HttpRequestContext {
  def withRetriesDecremented = copy(retriesLeft = retriesLeft - 1)
}