package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*
import tastyquery.Types.*

import scala.collection.mutable.ListBuffer

// ============================================================================
// TASTy-based fs2-grpc Scanner
//
// Scans compiled Scala code via TASTy to find gRPC integrations.
// Output: "classA.methodB exposes/consumes ServiceC/rpcD"
//
// Server detection (Write): class extends *Fs2Grpc trait
//   → each implemented RPC method is a gRPC endpoint exposure
//
// Client detection (Read): val field of type *Fs2Grpc
//   → each call to field.rpcMethod(...) is a gRPC client consumption
// ============================================================================

class TastyFs2GrpcScanner(using ctx: Context) extends IntegrationScanner {

  private val Suffix = "Fs2Grpc"

  def scan(packages: List[String]): List[DiscoveredIntegration] =
    packages.flatMap(scanPackage)

  private def scanPackage(packageName: String): List[DiscoveredIntegration] = {
    val classes = TastyUtils.userClasses(ctx.findPackage(packageName))
    classes.flatMap { cls =>
      scanServer(cls) ++ scanClient(cls)
    }
  }

  /** Server: class extends *Fs2Grpc trait → Write integrations for each implemented RPC method. */
  private def scanServer(cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString
    // Use cls.parents (types) instead of cls.parentClasses (symbols) because
    // parentClasses throws when it can't resolve java.lang.Object in the classpath.
    val grpcParentSymbols = try cls.parents.flatMap { parentType =>
      extractTypeRef(parentType)
        .filter(_.name.toString.endsWith(Suffix))
        .flatMap(tr => try tr.optSymbol catch { case _: Exception => None })
        .collect { case cs: ClassSymbol => cs }
    } catch { case _: Exception => Nil }

    grpcParentSymbols.flatMap { parent =>
      val parentName = parent.name.toString
      val serviceName = parentName.stripSuffix(Suffix)
      val parentMethodNames = parent.declarations.collect {
        case ts: TermSymbol if isRpcDeclaration(ts) => ts.name.toString
      }.toSet

      cls.declarations.collect {
        case ts: TermSymbol if parentMethodNames.contains(ts.name.toString) =>
          val methodName = ts.name.toString
          DiscoveredIntegration(
            method = MethodRef(className, methodName),
            accessType = DataAccessType.Write,
            integrationType = "grpc",
            target = s"$serviceName/$methodName",
            evidence = s"implements $parentName",
            group = Some(serviceName),
          )
      }
    }
  }

  private def extractTypeRef(tpe: TypeOrMethodic): Option[TypeRef] =
    TastyUtils.extractTypeRef(tpe)

  /** Client: val fields of type *Fs2Grpc → Read integrations for each call to those fields. */
  private def scanClient(cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString
    val grpcFields = resolveGrpcFieldTypes(cls)
    if (grpcFields.isEmpty) return Nil

    cls.declarations.collect {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        ts.tree.toList.flatMap {
          case defDef: DefDef =>
            defDef.rhs.toList.flatMap { rhs =>
              val collector = new GrpcClientCallCollector(grpcFields)
              collector.traverse(rhs)
              collector.calls.distinct.map { call =>
                DiscoveredIntegration(
                  method = MethodRef(className, methodName),
                  accessType = DataAccessType.Read,
                  integrationType = "grpc",
                  target = s"${call.serviceName}/${call.rpcMethod}",
                  evidence = s"calls ${call.fieldName}.${call.rpcMethod}",
                  group = Some(call.serviceName),
                )
              }
            }
          case _ => Nil
        }
    }.flatten
  }

  /** Resolve val field types ending in Fs2Grpc. Returns Map[fieldName → serviceName]. */
  private def resolveGrpcFieldTypes(cls: ClassSymbol): Map[String, String] =
    cls.declarations.collect {
      case ts: TermSymbol if !ts.name.toString.startsWith("<") && !ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val typeName = extractTypeRef(ts.declaredType).map(_.name.toString)
        typeName.filter(_.endsWith(Suffix)).map(n => ts.name.toString -> n.stripSuffix(Suffix))
    }.flatten.toMap

  private def isRpcDeclaration(ts: TermSymbol): Boolean = {
    val name = ts.name.toString
    !name.startsWith("<") && !name.startsWith("$")
  }

  private case class GrpcCall(fieldName: String, serviceName: String, rpcMethod: String)

  private class GrpcClientCallCollector(
      grpcFields: Map[String, String],
  ) extends TreeTraverser {
    val calls: ListBuffer[GrpcCall] = ListBuffer.empty

    override def traverse(tree: Tree): Unit = {
      tree match {
        case Apply(Select(Ident(fieldName), methodName), _) =>
          addIfGrpc(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case Apply(TypeApply(Select(Ident(fieldName), methodName), _), _) =>
          addIfGrpc(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case _ =>
      }
      super.traverse(tree)
    }

    private def addIfGrpc(fieldName: String, methodName: String): Unit =
      grpcFields.get(fieldName).foreach { serviceName =>
        calls += GrpcCall(fieldName, serviceName, methodName)
      }
  }
}
