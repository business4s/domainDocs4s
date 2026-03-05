package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Names.{Name, SignedName}
import tastyquery.Symbols.{ClassSymbol, PackageSymbol, Symbol}
import tastyquery.Types.*

private[lineage] object TastyUtils {
  def userClasses(pkg: PackageSymbol)(using Context): List[ClassSymbol] =
    pkg.declarations.collect {
      case cls: ClassSymbol if isUserClass(cls) => cls
    }

  /** Get module classes (Scala objects) from a package. */
  def moduleClasses(pkg: PackageSymbol)(using Context): List[ClassSymbol] =
    pkg.declarations.collect {
      case cls: ClassSymbol if isModuleClass(cls) => cls
    }

  private def isUserClass(cls: ClassSymbol): Boolean = {
    val name = cls.name.toString
    !name.endsWith("$") && !name.startsWith("<")
  }

  private def isModuleClass(cls: ClassSymbol): Boolean = {
    val name = cls.name.toString
    name.endsWith("$") && !name.startsWith("<")
  }

  /** Extract the simple name from a TASTy Name, unwrapping SignedName if needed.
    *
    * TASTy method names may be SignedNames that include type signatures,
    * e.g. `readJournalFor[with sig (1,java.lang.String):java.lang.Object @readJournalFor]`.
    * This extracts the underlying simple name string.
    */
  def simpleName(name: Name): String = name match {
    case SignedName(underlying, _, _) => underlying.toString
    case other                        => other.toString
  }

  /** Check if a TASTy Name matches a target string, unwrapping SignedName if needed. */
  def matchesName(name: Name, target: String): Boolean =
    simpleName(name) == target

  /** Extract the underlying TypeRef from a Type, unwrapping AppliedType if needed. */
  def extractTypeRef(tpe: TypeOrMethodic): Option[TypeRef] = tpe match {
    case tr: TypeRef                                       => Some(tr)
    case at: AppliedType if at.tycon.isInstanceOf[TypeRef] => Some(at.tycon.asInstanceOf[TypeRef])
    case _                                                 => None
  }

  /** Extract type name from a Type, unwrapping AppliedType and AndType. */
  def extractTypeName(tpe: TypeOrMethodic): Option[String] = tpe match {
    case tr: TypeRef     => Some(tr.name.toString)
    case at: AppliedType => extractTypeRef(at).map(_.name.toString)
    case at: AndType     => extractTypeName(at.first).orElse(extractTypeName(at.second))
    case _               => None
  }

  /** Extract the package name from a TypeRef's prefix. */
  def typeRefPackage(tr: TypeRef): String =
    try {
      tr.prefix match {
        case pr: PackageRef => pr.symbol.fullName.toString
        case _              => ""
      }
    } catch { case _: Exception => "" }

  /** Extract the fully qualified name from a TASTy type. */
  def extractFqn(tpe: TypeOrMethodic): Option[String] =
    extractTypeRef(tpe) match {
      case Some(tr) =>
        val pkg = typeRefPackage(tr)
        val name = tr.name.toString.stripSuffix("$")
        if (pkg.nonEmpty) Some(s"$pkg.$name") else Some(name)
      case None =>
        tpe match {
          case at: AndType => extractFqn(at.first).orElse(extractFqn(at.second))
          case _           => None
        }
    }

  /** Resolve a type to its symbol, safely handling TypeRef and AppliedType. */
  def resolveSymbol(tpe: TypeOrMethodic)(using Context): Option[Symbol] =
    tpe match {
      case tr: TypeRef     => try tr.optSymbol catch { case _: Exception => None }
      case at: AppliedType => extractTypeRef(at).flatMap(tr => try tr.optSymbol catch { case _: Exception => None })
      case _               => None
    }
}

/** Extract (packageName, className) from a ClassTag's runtime class. */
private[lineage] def splitClassTag(ct: reflect.ClassTag[?]): (String, String) = {
  val fqn = ct.runtimeClass.getName.stripSuffix("$")
  val lastDot = fqn.lastIndexOf('.')
  if (lastDot >= 0) (fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
  else ("", fqn)
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

/** Type-safe wrapper for resource type identifiers.
  *
  * Zero-overhead opaque type over String. Use predefined constants for
  * built-in types (Database, Kafka, etc.) or `ResourceType("custom")` for
  * user-defined types.
  */
opaque type ResourceType = String

object ResourceType {
  val Database: ResourceType = "database"
  val Kafka: ResourceType    = "kafka"
  val Grpc: ResourceType     = "grpc"
  val S3: ResourceType       = "s3"

  def apply(value: String): ResourceType = value

  extension (rt: ResourceType) def value: String = rt

  given Ordering[ResourceType] = Ordering.String
}

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
case class MethodRef(packageName: String, className: String, methodName: String) {
  def display: String = s"$className.$methodName"
}

/** A method extracted from TASTy — call graph input for lineage building. */
case class ExtractedMethod(
    className: String,
    packageName: String,
    methodName: String,
    calls: List[MethodRef],
) {
  def ref: MethodRef = MethodRef(packageName, className, methodName)
}

// ── Phase 1: Scanner output ──────────────────────────────────────────────────

/** Common interface for integration scanners that require TASTy packages. */
trait IntegrationScanner {
  def scan(packages: List[String]): List[DiscoveredIntegration]
}

/** Common interface for resource scanners (no TASTy needed). */
trait ResourceScanner {
  def scan(): List[DiscoveredIntegration]
}

/** A discovered external integration — output of any scanner.
  *
  * Each scanner type (doobie, kafka, grpc, ...) produces these.
  * The lineage builder consumes them generically.
  */
case class DiscoveredIntegration(
    method: MethodRef,
    accessType: DataAccessType,
    resourceType: ResourceType,   // ResourceType.Database, .Kafka, .Grpc, .Journal, .S3, or custom
    scanner: String,              // "doobie", "slick", "flyway", "grpc", "pekko-journal", "manual"
    target: String,               // what was accessed: table name, topic, endpoint, ...
    evidence: String,             // source evidence: SQL query, config key, ...
    group: Option[String] = None, // logical group: service name, database, ...
)

/** Metadata about one way a resource was found/accessed. */
case class Discovery(
    method: MethodRef,
    accessType: DataAccessType,
    scanner: String,
    evidence: String,
)

/** Merged physical resource — one per unique (target, resourceType, group). */
case class DiscoveredResource(
    target: String,
    resourceType: ResourceType,
    group: Option[String] = None,
    discoveries: List[Discovery] = Nil,
)

object DiscoveredResource {

  /** Merge flat integrations into deduplicated resources. */
  def merge(integrations: List[DiscoveredIntegration]): List[DiscoveredResource] =
    integrations
      .groupBy(i => (i.target, i.resourceType, i.group))
      .map { case ((target, resourceType, group), dis) =>
        DiscoveredResource(
          target = target,
          resourceType = resourceType,
          group = group,
          discoveries = dis.map(i => Discovery(i.method, i.accessType, i.scanner, i.evidence)),
        )
      }
      .toList
      .sortBy(r => (r.resourceType, r.target))
}

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
      entries += (splitClassTag(reflect.classTag[T])._2 -> groupName)
      this
    }
    def build: IntegrationGroupConfig = IntegrationGroupConfig(entries.toMap)
  }
  def builder: Builder = new Builder
}

/** How to group class nodes in class-level diagrams. */
sealed trait ClassGrouping
object ClassGrouping {
  case object NoGrouping extends ClassGrouping
  case class ByPackage(scanBase: String) extends ClassGrouping
  case class Custom(groupOf: ScannedClass => Option[String]) extends ClassGrouping
}

/** Configuration for class-level Mermaid rendering.
  *
  * Note: to hide classes (remove them while reconnecting callers → callees),
  * use `LineageAdjustments.builder.cls[T].remove` instead — this operates at
  * the data level so it works for all diagram types.
  */
case class ClassLevelConfig(
    foldByGroup: Set[ResourceType] = Set(ResourceType.Grpc),
    classGrouping: ClassGrouping = ClassGrouping.NoGrouping,
)

object ClassLevelConfig {
  class Builder {
    private var _foldByGroup: Set[ResourceType] = Set(ResourceType.Grpc)
    private var _classGrouping: ClassGrouping    = ClassGrouping.NoGrouping

    def foldByGroup(types: Set[ResourceType]): Builder = { _foldByGroup = types; this }
    def groupByPackage(scanBase: String): Builder = { _classGrouping = ClassGrouping.ByPackage(scanBase); this }
    def groupClassesBy(fn: ScannedClass => Option[String]): Builder = { _classGrouping = ClassGrouping.Custom(fn); this }
    def build: ClassLevelConfig = ClassLevelConfig(_foldByGroup, _classGrouping)
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
    classDisplayNames: Map[(String, String), String] = Map.empty,
) {
  lazy val allMethods: List[ScannedMethod] = classes.flatMap(_.methods)
  lazy val resources: List[DiscoveredResource] = DiscoveredResource.merge(integrations)

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
          sb.append(s"        ${i.scanner}(${i.resourceType}): ${i.accessType} ${i.target} [${i.evidence}]\n")
        for (call <- m.calls)
          sb.append(s"        -> calls ${call.display}\n")
      }
    }

    sb.append("\n=== Data Lineage Chains ===\n")
    for (chain <- lineageChains) {
      val pathStr = chain.path.map(_.display).mkString(" -> ")
      sb.append(s"\n  ${chain.integration.scanner}(${chain.integration.resourceType}): ${chain.integration.accessType} ${chain.integration.target}\n")
      sb.append(s"    $pathStr\n")
      sb.append(s"    evidence: ${chain.integration.evidence}\n")
    }

    sb.toString()
  }
}
