/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.core.server.akkahttp

import java.net.{ InetAddress, InetSocketAddress, URI }
import java.util.Locale

import akka.http.javadsl.model.headers.RawRequestURI
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.util.FastFuture._
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.Logger
import play.api.http.HeaderNames._
import play.api.http.{ HeaderNames, HttpChunk, HttpErrorHandler, Status, HttpEntity => PlayHttpEntity }
import play.api.libs.typedmap.TypedMap
import play.api.mvc._
import play.api.mvc.request.{ RemoteConnection, RequestTarget }
import play.core.server.common.{ ForwardedHeaderHandler, ServerResultUtils }
import play.core.utils.CaseInsensitiveOrdered

import scala.collection.immutable
import scala.collection.mutable.ListBuffer
import scala.collection.immutable.TreeSet
import scala.concurrent.Future

/**
 * Conversions between Akka's and Play's HTTP model objects.
 */
private[server] class AkkaModelConversion(
    resultUtils: ServerResultUtils,
    forwardedHeaderHandler: ForwardedHeaderHandler) {

  private val logger = Logger(getClass)

  /**
   * Convert an Akka `HttpRequest` to a `RequestHeader` and an `Enumerator`
   * for its body.
   */
  def convertRequest(
    requestId: Long,
    remoteAddress: InetSocketAddress,
    secureProtocol: Boolean,
    request: HttpRequest)(implicit fm: Materializer): (RequestHeader, Option[Source[ByteString, Any]]) = {
    (
      convertRequestHeader(requestId, remoteAddress, secureProtocol, request),
      convertRequestBody(request)
    )
  }

  /**
   * Convert an Akka `HttpRequest` to a `RequestHeader`.
   */
  private def convertRequestHeader(
    requestId: Long,
    remoteAddress: InetSocketAddress,
    secureProtocol: Boolean,
    request: HttpRequest): RequestHeader = {

    val headers = convertRequestHeadersAkka(request)
    val remoteAddressArg = remoteAddress // Avoid clash between method arg and RequestHeader field

    new RequestHeaderImpl(
      forwardedHeaderHandler.forwardedConnection(
        new RemoteConnection {
          override def remoteAddress: InetAddress = remoteAddressArg.getAddress
          override def secure: Boolean = secureProtocol
          // TODO - Akka does not yet expose the SSLEngine used for the request
          override lazy val clientCertificateChain = None
        },
        headers),
      request.method.name,
      new RequestTarget {
        override lazy val uri: URI = new URI(headers.uri)
        override lazy val uriString: String = headers.uri
        override lazy val path: String = request.uri.path.toString
        override lazy val queryMap: Map[String, Seq[String]] = request.uri.query().toMultiMap
      },
      request.protocol.value,
      headers,
      TypedMap.empty,
      Map.empty
    )
  }

  /**
   * Convert the request headers of an Akka `HttpRequest` to a Play `Headers` object.
   */
  private def convertRequestHeaders(request: HttpRequest): (Headers, String) = {
    var requestUri: String = null
    val headerBuffer = new ListBuffer[(String, String)]()
    headerBuffer += CONTENT_TYPE -> request.entity.contentType.value

    request.entity match {
      case HttpEntity.Strict(contentType, data) =>
        if (request.method.requestEntityAcceptance == RequestEntityAcceptance.Expected || data.nonEmpty)
          headerBuffer += CONTENT_LENGTH -> data.length.toString

      case HttpEntity.Default(contentType, contentLength, _) =>
        if (request.method.requestEntityAcceptance == RequestEntityAcceptance.Expected || contentLength > 0)
          headerBuffer += CONTENT_LENGTH -> contentLength.toString

      case _: HttpEntity.Chunked =>
        headerBuffer += TRANSFER_ENCODING -> play.api.http.HttpProtocol.CHUNKED
    }
    request.headers
      .foreach {
        case `Raw-Request-URI`(uri) => requestUri = uri
        case header => headerBuffer += header.name -> header.value
      }

    val uri =
      if (requestUri == null) {
        logger.warn("Can't get raw request URI. Raw-Request-URI was missing.")
        request.uri.toString
      } else
        requestUri

    (new Headers(headerBuffer.result()), uri)
  }

  /**
   * Convert the request headers of an Akka `HttpRequest` to a Play `Headers` object.
   */
  private def convertRequestHeadersAkka(request: HttpRequest): AkkaRequestHeaders = {
    var contentLength: Long = -1L
    val contentType: Option[String] =
      if (request.entity.isKnownEmpty()) None
      else Some(request.entity.contentType.value)
    var isChunked = false

    request.entity match {
      case HttpEntity.Strict(cType, data) =>
        if (request.method.requestEntityAcceptance == RequestEntityAcceptance.Expected || data.nonEmpty) {
          contentLength = data.length
        }
      case HttpEntity.Default(cType, cLength, _) =>
        if (request.method.requestEntityAcceptance == RequestEntityAcceptance.Expected || cLength > 0) {
          contentLength = cLength
        }
      case _: HttpEntity.Chunked =>
        isChunked = true
    }

    var requestUri: String = null
    val hs = request.headers.foreach {
      case `Raw-Request-URI`(u) => requestUri = u
      case _ =>
    }
    if (requestUri == null) requestUri = request.uri.toString

    AkkaRequestHeaders(request, requestUri, contentLength, contentType, request.headers, isChunked)
  }

  /**
   * Convert an Akka `HttpRequest` to an `Enumerator` of the request body.
   */
  private def convertRequestBody(
    request: HttpRequest)(implicit fm: Materializer): Option[Source[ByteString, Any]] = {
    request.entity match {
      case HttpEntity.Strict(_, data) if data.isEmpty =>
        None
      case HttpEntity.Strict(_, data) =>
        Some(Source.single(data))
      case HttpEntity.Default(_, 0, _) =>
        None
      case HttpEntity.Default(contentType, contentLength, pubr) =>
        // FIXME: should do something with the content-length?
        Some(pubr)
      case HttpEntity.Chunked(contentType, chunks) =>
        // FIXME: do something with trailing headers?
        Some(chunks.takeWhile(!_.isLastChunk).map(_.data()))
    }
  }

  /**
   * Convert a Play `Result` object into an Akka `HttpResponse` object.
   */
  def convertResult(
    requestHeaders: RequestHeader,
    originalResult: Result,
    protocol: HttpProtocol,
    errorHandler: HttpErrorHandler)(implicit mat: Materializer): Future[HttpResponse] = {

    import play.core.Execution.Implicits.trampoline
    val result: Result = resultUtils.prepareCookies(requestHeaders, originalResult)

    resultUtils.resultConversionWithErrorHandling(requestHeaders, result, errorHandler) { unvalidated =>
      // Convert result
      resultUtils.validateResult(requestHeaders, unvalidated, errorHandler).fast.map { validated: Result =>
        val convertedHeaders = convertHeaders(validated.header.headers)
        val entity = convertResultBody(requestHeaders, validated, protocol)
        val response = HttpResponse(
          status = validated.header.status,
          headers = convertedHeaders,
          entity = entity,
          protocol = protocol
        )
        response
      }
    } {
      // Fallback response in case an exception is thrown during normal error handling
      HttpResponse(
        status = Status.INTERNAL_SERVER_ERROR,
        headers = immutable.Seq(Connection("close")),
        entity = HttpEntity.Empty,
        protocol = protocol
      )
    }
  }

  def parseContentType(contentType: Option[String]): ContentType = {
    // actually play allows content types to be not spec compliant
    // so we can't rely on the parsed content type of akka
    contentType.fold(ContentTypes.NoContentType: ContentType) { ct =>
      MediaType.custom(ct, binary = true) match {
        case b: MediaType.Binary => ContentType(b)
        case _ => ContentTypes.NoContentType
      }
    }
  }

  def convertResultBody(
    requestHeaders: RequestHeader,
    result: Result,
    protocol: HttpProtocol): ResponseEntity = {

    val contentType = parseContentType(result.body.contentType)

    result.body match {
      case PlayHttpEntity.Strict(data, _) =>
        HttpEntity.Strict(contentType, data)

      case PlayHttpEntity.Streamed(data, Some(contentLength), _) =>
        HttpEntity.Default(contentType, contentLength, data)

      case PlayHttpEntity.Streamed(data, _, _) =>
        HttpEntity.CloseDelimited(contentType, data)

      case PlayHttpEntity.Chunked(data, _) =>
        val akkaChunks = data.map {
          case HttpChunk.Chunk(chunk) =>
            HttpEntity.Chunk(chunk)
          case HttpChunk.LastChunk(trailers) if trailers.headers.isEmpty =>
            HttpEntity.LastChunk
          case HttpChunk.LastChunk(trailers) =>
            HttpEntity.LastChunk(trailer = convertHeaders(trailers.headers))
        }
        HttpEntity.Chunked(contentType, akkaChunks)
    }
  }

  private def convertHeaders(headers: Iterable[(String, String)]): immutable.Seq[HttpHeader] =
    headers.collect {
      case (name, value) if name != TRANSFER_ENCODING =>
        RawHeader(name, value)
    }(collection.breakOut): Vector[HttpHeader]
}

final case class AkkaRequestHeaders(
    request: HttpRequest,
    uri: String,
    override val contentLength: Long,
    contentType: Option[String],
    hs: immutable.Seq[HttpHeader],
    isChunked: Boolean
) extends Headers(null) {

  override def headers: Seq[(String, String)] =
    if (_headers ne null) _headers
    else {
      _headers = hs.map(h => h.name() -> h.value)
      _headers
    }

  // note that these are rarely used, mostly just in tests
  override def add(headers: (String, String)*): AkkaRequestHeaders =
    copy(hs = this.hs ++ raw(headers))

  override def apply(key: String): String =
    get(key).getOrElse(throw new RuntimeException(s"Header with name ${key} not found!"))

  override def get(key: String): Option[String] =
    key match {
      case HeaderNames.CONTENT_LENGTH => if (contentLength > 0) Some(contentLength.toString) else None
      case HeaderNames.CONTENT_TYPE => contentType
      case _ =>
        val lower = key.toLowerCase(Locale.ROOT)
        hs.collectFirst({ case h if h.lowercaseName == lower => h.value })
    }

  // note that these are rarely used, mostly just in tests
  override def getAll(key: String): immutable.Seq[String] =
    hs.collect({ case h if h.is(key) => h.value })

  override lazy val keys: immutable.Set[String] =
    hs.map(_.name).toSet

  // note that these are rarely used, mostly just in tests
  override def remove(keys: String*): Headers =
    copy(hs = hs.filterNot(keys.contains))

  // note that these are rarely used, mostly just in tests
  override def replace(headers: (String, String)*): Headers = {
    val replaced = hs.filterNot(h => headers.exists(rm => h.is(rm._1))) ++ raw(headers)
    copy(hs = replaced)
  }

  override def equals(other: Any): Boolean = {
    other match {
      case that: AkkaRequestHeaders => that.request == this.request
      case _ => false
    }
  }

  private def raw(headers: Seq[(String, String)]): Seq[RawHeader] =
    headers.map(t => RawHeader(t._1, t._2))

  override def hashCode: Int =
    request.hashCode() // somewhat lazy way but should be correct

}
