/**
 * Copyright 2012-2013 StackMob
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

package com.stackmob.newman

import java.net.URL
import com.stackmob.newman.request._
import scalaz.concurrent.{Strategy, Promise}
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import com.twitter.util.{Future => TwitterFuture, Duration}
import com.twitter.finagle.http._
import com.twitter.finagle.builder.ClientBuilder
import org.jboss.netty.handler.codec.http.{HttpResponse => NettyHttpResponse, HttpRequest => NettyHttpRequest, HttpMethod => NettyHttpMethod, HttpResponseStatus}
import java.nio.ByteBuffer
import org.jboss.netty.buffer.{ChannelBuffer, ByteBufferBackedChannelBuffer}
import scalaz.effect.IO
import scalaz.Scalaz._
import collection.JavaConverters._
import FinagleHttpClient._

class FinagleHttpClient(tcpConnectionTimeout: Duration = DefaultTcpConnectTimeout) extends HttpClient {

  override def get(u: URL, h: Headers): GetRequest = new GetRequest {
    override lazy val url = u
    override lazy val headers = h
    override def prepareAsync: IO[Promise[HttpResponse]] = {
      IO {
        executeRequest(tcpConnectionTimeout, NettyHttpMethod.GET, url, headers)
      }
    }
  }

  override def post(u: URL, h: Headers, b: RawBody): PostRequest = new PostRequest {
    override lazy val url = u
    override lazy val headers = h
    override lazy val body = b
    override def prepareAsync: IO[Promise[HttpResponse]] = {
      IO {
        executeRequest(tcpConnectionTimeout, NettyHttpMethod.POST, url, headers, Some(body))
      }
    }
  }

  override def put(u: URL, h: Headers, b: RawBody): PutRequest = new PutRequest {
    override lazy val url = u
    override lazy val headers = h
    override lazy val body = b
    override def prepareAsync: IO[Promise[HttpResponse]] = {
      IO {
        executeRequest(tcpConnectionTimeout, NettyHttpMethod.PUT, url, headers, Some(body))
      }
    }
  }

  override def delete(u: URL, h: Headers): DeleteRequest = new DeleteRequest {
    override lazy val url = u
    override lazy val headers = h
    override def prepareAsync: IO[Promise[HttpResponse]] = {
      IO {
        executeRequest(tcpConnectionTimeout, NettyHttpMethod.DELETE, url, headers)
      }
    }
  }

  override def head(u: URL, h: Headers): HeadRequest = new HeadRequest {
    override lazy val url = u
    override lazy val headers = h
    override def prepareAsync: IO[Promise[HttpResponse]] = {
      IO {
        executeRequest(tcpConnectionTimeout, NettyHttpMethod.HEAD, url, headers)
      }
    }
  }
}

object FinagleHttpClient {

  def executeRequest(tcpConnectionTimeout: Duration,
                     method: NettyHttpMethod,
                     url: URL,
                     headers: Headers,
                     mbBody: Option[RawBody] = None): Promise[HttpResponse] = {
    val client = createClient(url, tcpConnectionTimeout)
    val req = createNettyHttpRequest(method, url, headers, mbBody)
    client(req).toScalaPromise.map { res =>
      res.toNewmanHttpResponse | {
        throw new InvalidNettyResponse(res.getStatus)
      }
    }
  }

  def createClient(url: URL, tcpConnectionTimeout: Duration) = {
    val host = url.getHost
    val port = url.getPort match {
      case -1 => 80
      case other => other
    }

    ClientBuilder()
      .codec(Http())
      .hosts("%s:%s".format(host, port))
      .hostConnectionLimit(1)
      .tcpConnectTimeout(tcpConnectionTimeout)
      .build()
  }

  def createNettyHttpRequest(method: NettyHttpMethod,
                             url: URL,
                             headers: Headers,
                             mbBody: Option[RawBody]): NettyHttpRequest = {
    val headersMap = headers.map { headerList =>
      headerList.list.toMap
    } | {
      Map[String, String]()
    }

    val mbChannelBuf: Option[ChannelBuffer] = mbBody.map { rawBody =>
      rawBody.toChannelBuf
    }
    RequestBuilder()
      .url(url)
      .addHeaders(headersMap)
      .build(method, mbChannelBuf)
  }

  implicit class RichRawBody(rawBody: RawBody) {
    def toChannelBuf: ChannelBuffer = {
      val byteBuf = ByteBuffer.wrap(rawBody)
      new ByteBufferBackedChannelBuffer(byteBuf)
    }
  }

  implicit class RichNettyHttpResponse(resp: NettyHttpResponse) {
    def toNewmanHttpResponse: Option[HttpResponse] = {
      for {
        code <- HttpResponseCode.fromInt(resp.getStatus.getCode)
        rawHeaders <- Option(resp.getHeaders)
        headers <- {
          val tupList = rawHeaders.asScala.map { entry =>
            entry.getKey -> entry.getValue
          }
          Option(tupList.toList.toNel)
        }
        body <- Option(resp.getContent.array)
      } yield {
        HttpResponse(code, headers, body)
      }
    }
  }

  implicit class TwitterFutureW[T](future: TwitterFuture[T]) {
    def toScalaPromise: Promise[T] = {
      val promise = Promise.emptyPromise[T](Strategy.Sequential)
      future.onSuccess { result =>
        promise.fulfill(result)
      }.onFailure { throwable =>
        promise.fulfill(throw throwable)
      }
      promise
    }
  }

  class InvalidNettyResponse(nettyCode: HttpResponseStatus) extends Exception(s"Invalid netty response with code: ${nettyCode.getCode}")
  val DefaultTcpConnectTimeout = Duration.fromMilliseconds(500)
}