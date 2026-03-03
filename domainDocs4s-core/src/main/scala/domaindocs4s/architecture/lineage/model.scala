package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.{ClassSymbol, PackageSymbol}

private[lineage] object TastyUtils {
  def userClasses(pkg: PackageSymbol)(using Context): List[ClassSymbol] =
    pkg.declarations.collect {
      case cls: ClassSymbol if isUserClass(cls) => cls
    }

  private def isUserClass(cls: ClassSymbol): Boolean = {
    val name = cls.name.toString
    !name.endsWith("$") && !name.startsWith("<")
  }
}

// ============================================================================
// Lineage Model — shared types across all phases
//
// Phase 0: TASTy extraction   -> ExtractedMethod (call graph)
// Phase 1: Scanners           -> DiscoveredIntegration (per-scanner)
// Phase 2: Lineage building   -> ScanResult (classes, lineage chains)
//
// Scanners are integration-specific (doobie, kafka, grpc, ...).
// The lineage builder is generic — works with any scanner output.
// The model captures ALL classes found — visualization trims later.
// ============================================================================

/** How a method accesses external resources. */
enum DataAccessType {
  case Read
  case Write
  case ReadWrite
  case Pure
}

object DataAccessType {

  def combine(a: DataAccessType, b: DataAccessType): DataAccessType = (a, b) match {
    case (Pure, other)  => other
    case (other, Pure)  => other
    case (Read, Read)   => Read
    case (Write, Write) => Write
    case _              => ReadWrite
  }

  def combineAll(types: List[DataAccessType]): DataAccessType =
    types.foldLeft(Pure: DataAccessType)(combine)
}

// ── Phase 0: TASTy extraction ────────────────────────────────────────────────

/** Reference to a specific method in a class. */
case class MethodRef(className: String, methodName: String) {
  def display: String = s"$className.$methodName"
}

/** A method extracted from TASTy — call graph input for lineage building. */
case class ExtractedMethod(
    className: String,
    packageName: String,
    methodName: String,
    calls: List[MethodRef],
) {
  def ref: MethodRef = MethodRef(className, methodName)
}

// ── Phase 1: Scanner output ──────────────────────────────────────────────────

/** A discovered external integration — output of any scanner.
  *
  * Each scanner type (doobie, kafka, grpc, ...) produces these.
  * The lineage builder consumes them generically.
  */
case class DiscoveredIntegration(
    method: MethodRef,
    accessType: DataAccessType,
    integrationType: String, // scanner name: "doobie", "kafka", "grpc", ...
    target: String,          // what was accessed: table name, topic, endpoint, ...
    evidence: String,        // source evidence: SQL query, config key, ...
    group: Option[String] = None, // logical group: service name, database, ...
)

case class IntegrationGroupConfig(
    classToGroup: Map[String, String] = Map.empty,
) {
  def enrich(integrations: List[DiscoveredIntegration]): List[DiscoveredIntegration] =
    integrations.map { di =>
      if (di.group.isDefined) di
      else classToGroup.get(di.method.className).fold(di)(g => di.copy(group = Some(g)))
    }
}

object IntegrationGroupConfig {
  class Builder {
    private val entries = scala.collection.mutable.Map.empty[String, String]
    def group[T: reflect.ClassTag](groupName: String): Builder = {
      entries += (reflect.classTag[T].runtimeClass.getSimpleName.stripSuffix("$") -> groupName)
      this
    }
    def build: IntegrationGroupConfig = IntegrationGroupConfig(entries.toMap)
  }
  def builder: Builder = new Builder
}

// ── Phase 2: Lineage builder output ──────────────────────────────────────────

case class ScannedMethod(
    ref: MethodRef,
    directAccess: DataAccessType,
    effectiveAccess: DataAccessType,
    calls: List[MethodRef],
    integrations: List[DiscoveredIntegration],
)

case class ScannedClass(
    name: String,
    packageName: String,
    methods: List[ScannedMethod],
) {
  def effectiveAccess: DataAccessType =
    DataAccessType.combineAll(methods.map(_.effectiveAccess))
}

case class CallEdge(caller: MethodRef, callee: MethodRef)

/** A lineage chain: entry point -> ... -> external integration. */
case class LineageChain(
    entryPoint: MethodRef,
    path: List[MethodRef],
    integration: DiscoveredIntegration,
)

/** Complete lineage result — all classes, call graph, and lineage chains. */
case class ScanResult(
    classes: List[ScannedClass],
    callGraph: List[CallEdge],
    integrations: List[DiscoveredIntegration],
    lineageChains: List[LineageChain],
) {
  lazy val allMethods: List[ScannedMethod] = classes.flatMap(_.methods)

  def findClass(name: String): Option[ScannedClass] =
    classes.find(_.name == name)

  def findMethod(ref: MethodRef): Option[ScannedMethod] =
    allMethods.find(_.ref == ref)

  def lineageForClass(className: String): List[LineageChain] =
    lineageChains.filter(_.path.exists(_.className == className))

  def lineageFrom(ref: MethodRef): List[LineageChain] =
    lineageChains.filter(_.entryPoint == ref)

  def prettyPrint: String = {
    val sb = new StringBuilder

    sb.append("=== Discovered Classes ===\n")
    for (cls <- classes) {
      sb.append(s"\n  ${cls.packageName}.${cls.name} [${cls.effectiveAccess}]\n")
      for (m <- cls.methods) {
        val directStr = if (m.directAccess != DataAccessType.Pure) s" (direct: ${m.directAccess})" else ""
        sb.append(s"    - ${m.ref.methodName}: ${m.effectiveAccess}$directStr\n")
        for (i <- m.integrations)
          sb.append(s"        ${i.integrationType}: ${i.accessType} ${i.target} [${i.evidence}]\n")
        for (call <- m.calls)
          sb.append(s"        -> calls ${call.display}\n")
      }
    }

    sb.append("\n=== Data Lineage Chains ===\n")
    for (chain <- lineageChains) {
      val pathStr = chain.path.map(_.display).mkString(" -> ")
      sb.append(s"\n  ${chain.integration.integrationType}: ${chain.integration.accessType} ${chain.integration.target}\n")
      sb.append(s"    $pathStr\n")
      sb.append(s"    evidence: ${chain.integration.evidence}\n")
    }

    sb.toString()
  }
}
