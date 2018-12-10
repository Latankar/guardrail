package com.twilio.guardrail.protocol.terms.server

import cats.InjectK
import cats.free.Free
import com.twilio.guardrail.generators.GeneratorSettings
import com.twilio.guardrail.{ RenderedRoutes, ServerRoute, StrictProtocolElems, TracingField }
import com.twilio.guardrail.languages.LA
import io.swagger.v3.oas.models.{ Operation, PathItem }

class ServerTerms[L <: LA, F[_]](implicit I: InjectK[ServerTerm[L, ?], F]) {
  def extractOperations(paths: List[(String, PathItem)]): Free[F, List[ServerRoute]] =
    Free.inject[ServerTerm[L, ?], F](ExtractOperations(paths))
  def getClassName(operation: Operation): Free[F, List[String]] =
    Free.inject[ServerTerm[L, ?], F](GetClassName(operation))
  def buildTracingFields(operation: Operation, resourceName: List[String], tracing: Boolean): Free[F, Option[TracingField[L]]] =
    Free.inject[ServerTerm[L, ?], F](BuildTracingFields(operation, resourceName, tracing))
  def generateRoutes(resourceName: String,
                     basePath: Option[String],
                     routes: List[(Option[TracingField[L]], ServerRoute)],
                     protocolElems: List[StrictProtocolElems[L]]): Free[F, RenderedRoutes[L]] =
    Free.inject[ServerTerm[L, ?], F](GenerateRoutes(resourceName, basePath, routes, protocolElems))
  def getExtraRouteParams(tracing: Boolean): Free[F, List[L#MethodParameter]] =
    Free.inject[ServerTerm[L, ?], F](GetExtraRouteParams(tracing))
  def generateResponseDefinitions(operation: Operation, protocolElems: List[StrictProtocolElems[L]]): Free[F, List[L#Definition]] =
    Free.inject[ServerTerm[L, ?], F](GenerateResponseDefinitions(operation, protocolElems))
  def renderClass(resourceName: String,
                  handlerName: String,
                  combinedRouteTerms: L#Term,
                  extraRouteParams: List[L#MethodParameter],
                  responseDefinitions: List[L#Definition],
                  supportDefinitions: List[L#Definition]): Free[F, List[L#Statement]] =
    Free.inject[ServerTerm[L, ?], F](RenderClass(resourceName, handlerName, combinedRouteTerms, extraRouteParams, responseDefinitions, supportDefinitions))
  def renderHandler(handlerName: String, methodSigs: List[L#MethodDeclaration], handlerDefinitions: List[L#Statement]) =
    Free.inject[ServerTerm[L, ?], F](RenderHandler(handlerName, methodSigs, handlerDefinitions))
  def getExtraImports(tracing: Boolean): Free[F, List[L#Import]] =
    Free.inject[ServerTerm[L, ?], F](GetExtraImports(tracing))
}

object ServerTerms {
  implicit def serverTerms[L <: LA, F[_]](implicit I: InjectK[ServerTerm[L, ?], F]): ServerTerms[L, F] =
    new ServerTerms[L, F]
}
