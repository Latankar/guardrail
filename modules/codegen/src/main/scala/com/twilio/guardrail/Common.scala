package com.twilio.guardrail

import java.net.{URI, URL}

import io.swagger.v3.oas.models.OpenAPI
import cats.data.NonEmptyList
import cats.free.Free
import cats.instances.all._
import cats.syntax.either._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import cats.~>
import com.twilio.guardrail.generators.GeneratorSettings
import com.twilio.guardrail.languages.ScalaLanguage
import com.twilio.guardrail.protocol.terms.protocol.PolyProtocolTerms
import com.twilio.guardrail.terms.framework.FrameworkTerms
import com.twilio.guardrail.terms.{CoreTerm, CoreTerms, ScalaTerms, SwaggerTerms}
import java.nio.file.{Path, Paths}

import scala.collection.JavaConverters._
import scala.io.AnsiColor
import scala.meta.{io, _}
import com.twilio.guardrail.languages.ScalaLanguage

import scala.Option
import scala.collection.immutable

object Common {

  // fixme: temporary means of using open-api v3 model without introducing too many changes at the same time
  // fixme: remove
  object OpenApiConversion {
    def schemes(serversUrls: List[String]): List[String] = { //fixme: toUpperCase ???
      serversUrls.map(s =>  new URI(s)).map(_.getScheme)
    }

    def host(serversUrls: List[String]): Option[String] = {
      for {
        list <- Option(serversUrls).filter(_.nonEmpty)
        head <- list.headOption
      } yield {
        new URI(head).getHost
      }
    }

    def basePath(serversUrls: List[String]): Option[String] = {
      for {
        list <- Option(serversUrls).filter(_.nonEmpty)
        head <- list.headOption
      } yield {
        new URI(head).getPath
      }
    }

  }


  def writePackage(kind: CodegenTarget,
                   context: Context,
                   swagger: OpenAPI,
                   outputPath: Path,
                   pkgName: List[String],
                   dtoPackage: List[String],
                   customImports: List[Import])(implicit F: FrameworkTerms[ScalaLanguage, CodegenApplication],
                                                Sc: ScalaTerms[ScalaLanguage, CodegenApplication],
                                                Pol: PolyProtocolTerms[ScalaLanguage, CodegenApplication],
                                                Sw: SwaggerTerms[ScalaLanguage, CodegenApplication]): Free[CodegenApplication, List[WriteTree]] = {
    import F._
    import Sc._
    import Sw._

    val resolveFile: Path => List[String] => Path       = root => _.foldLeft(root)(_.resolve(_))
    val splitComponents: String => Option[List[String]] = x => Some(x.split('.').toList).filterNot(_.isEmpty)

    val buildPackage: String => Option[Term.Ref] = pkg =>
      splitComponents(pkg)
        .map(dtoPackage ++ _)
        .map(_.map(Term.Name.apply _).reduceLeft(Term.Select.apply _))

    val pkgPath        = resolveFile(outputPath)(pkgName)
    val dtoPackagePath = resolveFile(pkgPath.resolve("definitions"))(dtoPackage)

    val definitions: List[String]   = pkgName :+ "definitions"
    val dtoComponents: List[String] = definitions ++ dtoPackage
    val buildPkgTerm: List[String] => Term.Ref =
      _.map(Term.Name.apply _).reduceLeft(Term.Select.apply _)

    for {
      proto <- ProtocolGenerator.fromSwagger[CodegenApplication](swagger)
      ProtocolDefinitions(protocolElems, protocolImports, packageObjectImports, packageObjectContents) = proto
      implicitsImport                                                                                  = q"import ${buildPkgTerm(List("_root_") ++ pkgName ++ List("Implicits"))}._"
      imports                                                                                          = customImports ++ protocolImports ++ List(implicitsImport)

      protoOut = protocolElems
        .map({
          case EnumDefinition(_, _, _, cls, obj) =>
            (List(
               WriteTree(
                 resolveFile(outputPath)(dtoComponents).resolve(s"${cls.name.value}.scala"),
                 source"""
              package ${buildPkgTerm(definitions)}
                ..${imports}
                $cls
                $obj
              """
               )
             ),
             List.empty[Stat])

          case ClassDefinition(_, _, cls, obj, _) =>
            (List(
               WriteTree(
                 resolveFile(outputPath)(dtoComponents).resolve(s"${cls.name.value}.scala"),
                 source"""
              package ${buildPkgTerm(dtoComponents)}
                ..${imports}
                $cls
                $obj
              """
               )
             ),
             List.empty[Stat])

          case ADT(name, tpe, trt, obj) =>
            val polyImports: Import = q"""import cats.syntax.either._"""

            (
              List(
                WriteTree(
                  resolveFile(outputPath)(dtoComponents).resolve(s"$name.scala"),
                  source"""
                    package ${buildPkgTerm(dtoComponents)}

                    ..$imports
                    $polyImports
                    $trt
                    $obj

                  """
                )
              ),
              List.empty[Stat]
            )

          case RandomType(_, _) =>
            (List.empty, List.empty)
        })
        .foldLeft((List.empty[WriteTree], List.empty[Stat]))(_ |+| _)
      (protocolDefinitions, extraTypes) = protoOut

      dtoHead :: dtoRest = dtoComponents
      dtoPkg = dtoRest.init
        .foldLeft[Term.Ref](Term.Name(dtoHead)) {
          case (acc, next) => Term.Select(acc, Term.Name(next))
        }
      companion = Term.Name(s"${dtoComponents.last}$$")

      (implicits, statements) = packageObjectContents.partition({ case q"implicit val $_: $_ = $_" => true; case _ => false })

      mirroredImplicits = implicits
        .map({ stat =>
          val List(Pat.Var(mirror)) = stat.pats
          stat.copy(rhs = q"${companion}.${mirror}")
        })

      packageObject <- writePackageObject(
        dtoPackagePath,
        dtoComponents,
        customImports,
        packageObjectImports,
        protocolImports,
        packageObjectContents,
        extraTypes
      )

      serverUrls = swagger.getServers.asScala.toList.map(_.getUrl)

      schemes = OpenApiConversion.schemes(serverUrls)
      host     = OpenApiConversion.host(serverUrls)
      basePath = OpenApiConversion.basePath(serverUrls)

      paths = Option(swagger.getPaths)
        .map(_.asScala.toList)
        .getOrElse(List.empty)

      routes           <- extractOperations(paths)
      classNamedRoutes <- routes.traverse(route => getClassName(route.operation).map(_ -> route))
      groupedRoutes = classNamedRoutes
        .groupBy(_._1)
        .mapValues(_.map(_._2))
        .toList
      frameworkImports   <- getFrameworkImports(context.tracing)
      frameworkImplicits <- getFrameworkImplicits()
      frameworkImplicitName = frameworkImplicits.name

      codegen <- kind match {
        case CodegenTarget.Client =>
          for {
            clientMeta <- ClientGenerator
              .fromSwagger[ScalaLanguage, CodegenApplication](context, frameworkImports)(schemes, host, basePath, groupedRoutes)(protocolElems)
            Clients(clients) = clientMeta
          } yield CodegenDefinitions[ScalaLanguage](clients, List.empty)

        case CodegenTarget.Server =>
          for {
            serverMeta <- ServerGenerator.fromSwagger[ScalaLanguage, CodegenApplication](context, swagger, frameworkImports)(protocolElems)
            Servers(servers) = serverMeta
          } yield CodegenDefinitions[ScalaLanguage](List.empty, servers)
      }

      CodegenDefinitions(clients, servers) = codegen

      files = clients
        .map({
          case Client(pkg, clientName, imports, companion, client, responseDefinitions) =>
            WriteTree(
              resolveFile(pkgPath)(pkg :+ s"${clientName}.scala"),
              source"""
                package ${buildPkgTerm(pkgName ++ pkg)}
                import ${buildPkgTerm(List("_root_") ++ pkgName ++ List("Implicits"))}._
                import ${buildPkgTerm(List("_root_") ++ pkgName ++ List(frameworkImplicitName.value))}._
                import ${buildPkgTerm(List("_root_") ++ dtoComponents)}._
                ..${customImports};
                ..${imports};
                ${companion};
                ${client};
                ..${responseDefinitions}
                """
            )
        }) ++ servers
          .map{
            case Server(pkg, extraImports, src) =>
              WriteTree(
                resolveFile(pkgPath)(pkg :+ "Routes.scala"),
                source"""
                    package ${buildPkgTerm((pkgName ++ pkg))}
                    ..${extraImports}
                    import ${buildPkgTerm(List("_root_") ++ pkgName ++ List("Implicits"))}._
                    import ${buildPkgTerm(List("_root_") ++ pkgName ++ List(frameworkImplicitName.value))}._
                    import ${buildPkgTerm(List("_root_") ++ dtoComponents)}._
                    ..${customImports}
                    ..$src
                    """
              )
          }

      implicits              <- renderImplicits(pkgName, frameworkImports, protocolImports, customImports)
      frameworkImplicitsFile <- renderFrameworkImplicits(pkgName, frameworkImports, protocolImports, frameworkImplicits)
    } yield
      (
        protocolDefinitions ++
          List(packageObject) ++
          files ++
          List(
            WriteTree(pkgPath.resolve("Implicits.scala"), implicits),
            WriteTree(pkgPath.resolve(s"${frameworkImplicitName.value}.scala"), frameworkImplicitsFile)
          )
      ).toList
  }

  def processArgs[F[_]](
      args: NonEmptyList[Args]
  )(implicit C: CoreTerms[F]): Free[F, NonEmptyList[(GeneratorSettings, ReadSwagger[Target[List[WriteTree]]])]] = {
    import C._
    args.traverse(
      arg =>
        for {
          targetInterpreter <- extractGenerator(arg.context)
          generatorSettings <- extractGeneratorSettings(arg.context)
          writeFile         <- processArgSet(targetInterpreter)(arg)
        } yield (generatorSettings, writeFile)
    )
  }

  def runM[F[_]](args: Array[String])(implicit C: CoreTerms[F]): Free[F, NonEmptyList[(GeneratorSettings, ReadSwagger[Target[List[WriteTree]]])]] = {
    import C._

    for {
      defaultFramework <- getDefaultFramework
      parsed           <- parseArgs(args, defaultFramework)
      args             <- validateArgs(parsed)
      writeTrees       <- processArgs(args)
    } yield writeTrees
  }
}
