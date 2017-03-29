/*
 * Copyright (C) 2009-2017 Lightbend Inc. <https://www.lightbend.com>
 */
package play.api.mvc

import play.api.libs.typedmap.{ TypedEntry, TypedKey, TypedMap }
import play.api.mvc.request.{ RemoteConnection, RequestTarget }

import scala.annotation.{ implicitNotFound, tailrec }

/**
 * The complete HTTP request.
 *
 * @tparam A the body content type.
 */
@implicitNotFound("Cannot find any HTTP Request here")
trait Request[+A] extends RequestHeader {
  self =>

  /**
   * True if this request has a body. This is either done by inspecting the body itself to see if it is an entity
   * representing an "empty" body.
   */
  override def hasBody: Boolean = {
    @tailrec @inline def isEmptyBody(body: Any): Boolean = body match {
      case rb: play.mvc.Http.RequestBody => isEmptyBody(rb.as(classOf[AnyRef]))
      case AnyContentAsEmpty | null | Unit => true
      case unit if unit.isInstanceOf[scala.runtime.BoxedUnit] => true
      case _ => false
    }
    !isEmptyBody(body) || super.hasBody
  }

  /**
   * The body content.
   */
  def body: A

  /**
   * Transform the request body.
   */
  def map[B](f: A => B): Request[B] = withBody(f(body))

  // Override the return type and default implementation of these RequestHeader methods
  override def withConnection(newConnection: RemoteConnection): Request[A]
  override def withMethod(newMethod: String): Request[A]
  override def withTarget(newTarget: RequestTarget): Request[A]
  override def withVersion(newVersion: String): Request[A]
  override def withHeaders(newHeaders: Headers): Request[A]
  override def withAttrs(newAttrs: TypedMap): Request[A]
  override def withTags(tags: Map[String, String]): Request[A]
}

object Request {
  /**
   * Create a new Request from a RequestHeader and a body. The RequestHeader's
   * methods aren't evaluated when this method is called.
   */
  def apply[A](rh: RequestHeader, body: A): Request[A] = rh.withBody(body)
}

/**
 * A standard implementation of a Request.
 *
 * @param body The body of the request.
 * @tparam A The type of the body content.
 */
private[play] class RequestImpl[+A](
    override val connection: RemoteConnection,
    override val method: String,
    override val target: RequestTarget,
    override val version: String,
    override val headers: Headers,
    override val attrs: TypedMap,
    val tags: Map[String, String],
    override val body: A) extends Request[A] {

  def withTags(tags: Map[String, String]): Request[A] = new RequestImpl(connection, method, target, version, headers, attrs, tags, body)
  def withAttrs(attrs: play.api.libs.typedmap.TypedMap): Request[A] = new RequestImpl(connection, method, target, version, headers, attrs, tags, body)
  def withConnection(connection: play.api.mvc.request.RemoteConnection): Request[A] = new RequestImpl(connection, method, target, version, headers, attrs, tags, body)
  def withHeaders(headers: play.api.mvc.Headers): Request[A] = new RequestImpl(connection, method, target, version, headers, attrs, tags, body)
  def withMethod(method: String): Request[A] = new RequestImpl(connection, method, target, version, headers, attrs, tags, body)
  def withTarget(target: play.api.mvc.request.RequestTarget): Request[A] = new RequestImpl(connection, method, target, version, headers, attrs, tags, body)
  def withVersion(version: String): Request[A] = new RequestImpl(connection, method, target, version, headers, attrs, tags, body)
}