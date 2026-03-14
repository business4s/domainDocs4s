package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Trees.*

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
//
// Note: uses fqnEndsWith("Fs2Grpc") because fs2-grpc generates a trait per
// service (e.g., UserServiceFs2Grpc) with no common base type. This suffix
// match may produce false positives if user code defines types ending in
// "Fs2Grpc". If that happens, switch to TypeMatcher.oneOf with explicit FQNs
// for the services you use, or use LineageAdjustments to filter out noise.
// ============================================================================

class TastyFs2GrpcScanner(using ctx: Context) extends IntegrationScanner {

  private val typeMatcher       = TypeMatcher.fqnEndsWith("Fs2Grpc")
  private val inheritanceSearch = SymbolSearch.ClassInheritance(typeMatcher)
  private val methodCallSearch  = SymbolSearch.MethodCall(typeMatcher)

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(inheritanceSearch, methodCallSearch))
    val usages = finder.findAll(packages)

    val serverResults = usages.collect { case u: FoundUsage.InheritanceResult => u }.flatMap(interpretServer)
    val clientResults = usages.collect { case u: FoundUsage.MethodCallResult => u }.map(interpretClient)

    serverResults ++ clientResults
  }

  // Server: class extends *Fs2Grpc → emit one Write per implemented RPC method
  private def interpretServer(u: FoundUsage.InheritanceResult): List[DiscoveredIntegration] = {
    val ref         = u.path.toMethodRef
    val serviceName = u.parentSimpleName.stripSuffix("Fs2Grpc")

    u.inheritedMethods.flatMap { method =>
      // Only emit if the class actually declares this method
      val classNode           = u.path.nodes.collectFirst { case c: NestingNode.ClassOrObject => c }
      val classTree           = classNode.flatMap(_.tree)
      val classDeclaresMethod = classTree.exists { cd =>
        cd.rhs.body.exists {
          case defDef: DefDef => defDef.name.toString == method
          case _              => false
        }
      }
      if (classDeclaresMethod) {
        List(
          DiscoveredIntegration(
            method = MethodRef(ref.packageName, ref.className, method),
            accessType = DataAccessType.Write,
            resourceType = ResourceType.Grpc,
            scanner = "grpc",
            target = s"$serviceName/$method",
            evidence = s"implements ${u.parentSimpleName}",
            group = Some(serviceName),
          ),
        )
      } else Nil
    }
  }

  // Client: field of type *Fs2Grpc → each call is a Read
  private def interpretClient(u: FoundUsage.MethodCallResult): DiscoveredIntegration = {
    val ref         = u.path.toMethodRef
    val serviceName = u.ownerSimpleName.stripSuffix("Fs2Grpc")
    DiscoveredIntegration(
      method = ref,
      accessType = DataAccessType.Read,
      resourceType = ResourceType.Grpc,
      scanner = "grpc",
      target = s"$serviceName/${u.methodName}",
      evidence = s"calls ${u.receiverName}.${u.methodName}",
      group = Some(serviceName),
    )
  }
}
