package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Names.{Name, SignedName}
import tastyquery.Symbols.{ClassSymbol, PackageSymbol, Symbol}
import tastyquery.Trees
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

  /** Recursively collect all subpackages (including the root package itself). */
  def allSubpackages(pkg: PackageSymbol)(using Context): List[PackageSymbol] = {
    val subPkgs = pkg.declarations.collect { case sub: PackageSymbol => sub }
    pkg :: subPkgs.flatMap(allSubpackages)
  }

  /** Recursively collect user classes from a package and all subpackages. */
  def userClassesRecursive(pkg: PackageSymbol)(using Context): List[(PackageSymbol, ClassSymbol)] =
    allSubpackages(pkg).flatMap(p => userClasses(p).map(p -> _))

  /** Recursively collect module classes from a package and all subpackages. */
  def moduleClassesRecursive(pkg: PackageSymbol)(using Context): List[(PackageSymbol, ClassSymbol)] =
    allSubpackages(pkg).flatMap(p => moduleClasses(p).map(p -> _))

  /** Collect classes nested inside module classes (companion objects). E.g., `object Foo { case class Impl(...) }` → finds `Impl` inside `Foo$`.
    * Returns (ownerPackage, nestedClass) pairs.
    */
  def nestedClassesInModules(pkg: PackageSymbol)(using Context): List[(PackageSymbol, ClassSymbol)] =
    allSubpackages(pkg).flatMap { p =>
      moduleClasses(p).flatMap { moduleCls =>
        moduleCls.declarations.collect {
          case cls: ClassSymbol if isUserClass(cls) => (p, cls)
        }
      }
    }

  /** Collect module classes (objects) nested inside other module classes. E.g., `object OuterJob { object Helpers { def process(...) } }` → finds
    * `Helpers$` inside `OuterJob$`. Returns (ownerPackage, nestedModuleClass) pairs.
    */
  def nestedModulesInModules(pkg: PackageSymbol)(using Context): List[(PackageSymbol, ClassSymbol)] =
    allSubpackages(pkg).flatMap { p =>
      moduleClasses(p).flatMap { moduleCls =>
        moduleCls.declarations.collect {
          case cls: ClassSymbol if isModuleClass(cls) => (p, cls)
        }
      }
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
    * TASTy method names may be SignedNames that include type signatures, e.g. `readJournalFor[with sig (1,java.lang.String):java.lang.Object
    * \@readJournalFor]`. This extracts the underlying simple name string.
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

  /** Extract the package name from a TypeRef's prefix. Walks the prefix chain to handle nested types (e.g., `object Foo { case class Impl(...) }`
    * where Impl's prefix is a TypeRef to Foo, not a PackageRef).
    */
  def typeRefPackage(tr: TypeRef, depth: Int = 0): String =
    if (depth > 10) ""
    else
      try {
        tr.prefix match {
          case pr: PackageRef  => pr.symbol.fullName.toString
          case parent: TypeRef => typeRefPackage(parent, depth + 1)
          case tt: ThisType    => typeRefPackage(tt.tref, depth + 1)
          case _               => ""
        }
      } catch { case _: Exception => "" }

  /** Extract the package name from a TermRef's prefix chain. */
  def termRefPackage(tr: TermRef, depth: Int = 0): String =
    if (depth > 10) ""
    else
      try {
        tr.prefix match {
          case pr: PackageRef  => pr.symbol.fullName.toString
          case parent: TermRef => termRefPackage(parent, depth + 1)
          case parent: TypeRef => typeRefPackage(parent, depth + 1)
          case _               => ""
        }
      } catch { case _: Exception => "" }

  /** Extract the fully qualified name from a TASTy type. */
  def extractFqn(tpe: TypeOrMethodic): Option[String] =
    extractTypeRef(tpe) match {
      case Some(tr) =>
        val pkg  = typeRefPackage(tr)
        val name = tr.name.toString.stripSuffix("$")
        if (pkg.nonEmpty) Some(s"$pkg.$name") else Some(name)
      case None     =>
        tpe match {
          case at: AndType => extractFqn(at.first).orElse(extractFqn(at.second))
          case _           => None
        }
    }

  /** Extract the type from a parent tree in a ClassDef's parent list. Handles both `new ParentType(args)` (Apply/TypeApply wrapping New) and bare
    * `TypeTree` references.
    */
  def resolveParentType(parentTree: Trees.Tree): Option[Type] = parentTree match {
    case Trees.Apply(Trees.Select(Trees.New(typeTree), _), _)                     => Some(typeTree.toType)
    case Trees.Apply(Trees.TypeApply(Trees.Select(Trees.New(typeTree), _), _), _) => Some(typeTree.toType)
    case typeTree: Trees.TypeTree                                                 => Some(typeTree.toType)
    case _                                                                        => None
  }

  /** Resolve a type to its symbol, safely handling TypeRef and AppliedType. */
  def resolveSymbol(tpe: TypeOrMethodic)(using Context): Option[Symbol] =
    tpe match {
      case tr: TypeRef     =>
        try tr.optSymbol
        catch { case _: Exception => None }
      case at: AppliedType =>
        extractTypeRef(at).flatMap(tr =>
          try tr.optSymbol
          catch { case _: Exception => None },
        )
      case _               => None
    }
}

/** Extract (packageName, className) from a ClassTag's runtime class. */
private[lineage] def splitClassTag(ct: reflect.ClassTag[?]): (String, String) = {
  val fqn     = ct.runtimeClass.getName.stripSuffix("$")
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
  * Zero-overhead opaque type over String. Use predefined constants for built-in types (Database, Kafka, etc.) or `ResourceType("custom")` for
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

// ── Resource identity ────────────────────────────────────────────────────────
//
// Type-safe hierarchical identifiers for external resources.
//
// Each known resource type (DB, S3, Kafka, gRPC) has a dedicated case class
// with typed fields for its natural hierarchy (e.g., cluster/database/schema/table).
// Custom resource types use Generic with freeform (level, value) segments.
//
// Hierarchy goes outermost → innermost. Outermost segments are often shared
// across a service (same cluster, same region) and can be omitted — they serve
// as grouping/disambiguation only when needed.
//
// ResourceId is purely about identity. Display concerns (folding, containers,
// subgraph nesting) are handled by ResourceDisplay + ResourceTypeDisplay config.
// ─────────────────────────────────────────────────────────────────────────────

sealed trait ResourceId {
  def resourceType: ResourceType
  def segments: List[(String, String)]

  /** Innermost segment value — the resource's leaf name. */
  def label: String = segments.lastOption.map(_._2).getOrElse("")

  /** Full dedup key — all segments plus resource type. */
  def key: String = s"${resourceType.value}:${segments.map((l, v) => s"$l=$v").mkString("/")}"

  /** Whether this resource was auto-detected but the target is unknown (needs override). */
  def isUnresolved: Boolean = label.startsWith("<unresolved:")

  override def toString: String = key
}

object ResourceId {

  // ── Database ──────────────────────────────────────────────────────────

  case class DbTable(
      table: String,
      schema: Option[String] = None,
      database: Option[String] = None,
      cluster: Option[String] = None,
  ) extends ResourceId {
    def resourceType: ResourceType = ResourceType.Database
    def segments: List[(String, String)] = List(
      cluster.map("cluster" -> _),
      database.map("database" -> _),
      schema.map("schema" -> _),
      Some("table" -> table),
    ).flatten
  }

  // ── S3 ────────────────────────────────────────────────────────────────

  case class S3Object(
      path: String,
      bucket: Option[String] = None,
      region: Option[String] = None,
  ) extends ResourceId {
    def resourceType: ResourceType = ResourceType.S3
    def segments: List[(String, String)] = List(
      region.map("region" -> _),
      bucket.map("bucket" -> _),
      Some("path" -> path),
    ).flatten

  }

  // ── Kafka ─────────────────────────────────────────────────────────────

  case class KafkaTopic(
      topic: String,
      cluster: Option[String] = None,
  ) extends ResourceId {
    def resourceType: ResourceType = ResourceType.Kafka
    def segments: List[(String, String)] = List(
      cluster.map("cluster" -> _),
      Some("topic" -> topic),
    ).flatten

  }

  // ── gRPC ──────────────────────────────────────────────────────────────

  case class GrpcEndpoint(
      service: String,
      method: String,
      host: Option[String] = None,
  ) extends ResourceId {
    def resourceType: ResourceType = ResourceType.Grpc
    def segments: List[(String, String)] = List(
      host.map("host" -> _),
      Some("service" -> service),
      Some("method" -> method),
    ).flatten

  }

  // ── Generic (for custom / extensible resource types) ──────────────────

  case class Generic(
      resourceType: ResourceType,
      segments: List[(String, String)],
  ) extends ResourceId

  // ── Unresolved placeholder ────────────────────────────────────────────

  /** Create an unresolved resource id. Scanners use this when they detect an integration but can't determine the actual target. The className and
    * methodName identify where the usage was found, so adjustments can match by class/method to provide the real resource.
    */
  def unresolved(resourceType: ResourceType, className: String, methodName: String): ResourceId =
    Generic(resourceType, List("target" -> s"<unresolved:$className.$methodName>"))
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

/** Source of an argument at a call site.
  *
  * Used by [[ParamValueIndex]] to trace string literals backward through arbitrary-depth call chains.
  */
sealed trait ArgSource
object ArgSource {

  /** A string literal directly at the call site. */
  case class Literal(value: String) extends ArgSource

  /** The arg was the Nth term parameter of the enclosing method — follow callers. */
  case class ForwardedParam(
      callerPkg: String,
      callerCls: String,
      callerMethod: String,
      paramIndex: Int,
  ) extends ArgSource

  /** A local val whose RHS was a known string literal at the time of collection. */
  case class LocalLiteral(value: String) extends ArgSource

  /** The arg was a local val that holds the result of calling `method` with these args. */
  case class CallResult(
      method: MethodRef,
      argSources: IndexedSeq[ArgSource],
  ) extends ArgSource

  case object Unresolvable extends ArgSource
}

// ── Phase 1: Scanner output ──────────────────────────────────────────────────

/** Common interface for integration scanners that require TASTy packages. */
trait IntegrationScanner {
  def scan(packages: List[String]): List[DiscoveredIntegration]
}

/** Common interface for resource scanners (no TASTy needed). */
trait ResourceScanner {
  def scan(): List[DiscoveredIntegration]
  def scanDependencies(): List[ResourceDependency] = Nil
}

/** A dependency between two resources (e.g., a VIEW depends on its source tables). */
case class ResourceDependency(
    from: ResourceId,
    to: ResourceId,
    label: String = "", // e.g., "view source"
)

/** A discovered external integration — output of any scanner.
  *
  * Each scanner type (doobie, kafka, grpc, ...) produces these. The lineage builder consumes them generically.
  */
case class DiscoveredIntegration(
    method: MethodRef,
    accessType: DataAccessType,
    resourceId: ResourceId,
    scanner: String, // "doobie", "slick", "flyway", "grpc", "pekko-journal", "manual"
    evidence: String, // source evidence: SQL query, config key, ...
) {
  def resourceType: ResourceType = resourceId.resourceType
  def target: String             = resourceId.label
}

/** Metadata about one way a resource was found/accessed. */
case class Discovery(
    method: MethodRef,
    accessType: DataAccessType,
    scanner: String,
    evidence: String,
)

/** Merged physical resource — one per unique ResourceId key. */
case class DiscoveredResource(
    resourceId: ResourceId,
    discoveries: List[Discovery] = Nil,
) {
  def target: String             = resourceId.label
  def resourceType: ResourceType = resourceId.resourceType
}

object DiscoveredResource {

  /** Merge flat integrations into deduplicated resources.
    *
    * First groups by exact ResourceId.key. Then merges resources of the same type and label where one is a less-specific version of another (fewer
    * segments). For example, `database:table=X` and `database:database=InternalDB/table=X` merge into the more specific one, combining all
    * discoveries. This handles the common case where code scanners (Doobie/Slick) detect table names without knowing which database they belong to,
    * while resource scanners (Flyway) know the full identity.
    */
  def merge(integrations: List[DiscoveredIntegration]): List[DiscoveredResource] = {
    // Phase 1: group by exact key
    val byKey = integrations
      .groupBy(_.resourceId.key)
      .map { case (_, dis) =>
        DiscoveredResource(
          resourceId = dis.head.resourceId,
          discoveries = dis.map(i => Discovery(i.method, i.accessType, i.scanner, i.evidence)),
        )
      }
      .toList

    // Phase 2: merge less-specific resources into more-specific ones with same type+label
    val byTypeAndLabel = byKey.groupBy(r => (r.resourceType, r.target))
    byTypeAndLabel.values.flatMap { group =>
      if (group.size <= 1) group
      else {
        // Pick the most specific ResourceId (most segments), merge all discoveries into it
        val sorted       = group.sortBy(-_.resourceId.segments.size)
        val mostSpecific = sorted.head
        val allDiscoveries = group.flatMap(_.discoveries)
        List(mostSpecific.copy(discoveries = allDiscoveries))
      }
    }.toList.sortBy(r => (r.resourceType, r.target))
  }
}

/** How to group class nodes in class-level diagrams. */
sealed trait ClassGrouping
object ClassGrouping {
  case object NoGrouping                                     extends ClassGrouping
  case class ByPackage(scanBase: String)                     extends ClassGrouping
  case class Custom(groupOf: ScannedClass => Option[String]) extends ClassGrouping
}

// ── Resource display configuration ───────────────────────────────────────────
//
// Separates display/rendering concerns from ResourceId (which is pure identity).
// Each resource type can have a virtual container label and a default fold level.
// Renderers use ResourceDisplay helpers to compute hierarchy from segments + config.
// ─────────────────────────────────────────────────────────────────────────────

/** Per-resource-type display configuration.
  *
  * @param containerLabel
  *   Virtual top-level container label (e.g., "S3", "gRPC"). When set, a container subgraph is prepended to the segment hierarchy even if no matching
  *   outermost segment exists in the ResourceId.
  * @param foldAtLevel
  *   Segment level name at which to fold in class-level diagrams. Resources sharing the same value at this level are collapsed into one node. Segments
  *   below the fold level become hidden detail.
  */
case class ResourceTypeDisplay(
    containerLabel: Option[String] = None,
    foldAtLevel: Option[String] = None,
)

/** Computes display information from ResourceId + ResourceTypeDisplay config.
  *
  * Pure utility — no mutable state, no side effects. Used by all renderers (Mermaid, JSON, Cytoscape viewer) to derive consistent hierarchy,
  * folding, and node IDs from the same identity + config.
  */
object ResourceDisplay {

  val defaults: Map[ResourceType, ResourceTypeDisplay] = Map(
    ResourceType.S3   -> ResourceTypeDisplay(containerLabel = Some("S3"), foldAtLevel = Some("bucket")),
    ResourceType.Grpc -> ResourceTypeDisplay(containerLabel = Some("gRPC"), foldAtLevel = Some("service")),
    ResourceType.Kafka -> ResourceTypeDisplay(containerLabel = Some("Kafka")),
  )

  /** Effective segments with virtual container prepended if configured. */
  def effectiveSegments(rid: ResourceId, config: Map[ResourceType, ResourceTypeDisplay]): List[(String, String)] = {
    val tc = config.getOrElse(rid.resourceType, ResourceTypeDisplay())
    tc.containerLabel match {
      case Some(label) => ("container" -> label) :: rid.segments
      case None        => rid.segments
    }
  }

  /** Index of the fold level within effective segments, or None if no fold configured or level not found. */
  def foldIndex(rid: ResourceId, config: Map[ResourceType, ResourceTypeDisplay]): Option[Int] = {
    val tc = config.getOrElse(rid.resourceType, ResourceTypeDisplay())
    tc.foldAtLevel.flatMap { level =>
      val segs = effectiveSegments(rid, config)
      val idx  = segs.indexWhere(_._1 == level)
      if (idx >= 0) Some(idx) else None
    }
  }

  /** Key for the folded node. If folding is active, includes segments up to and including the fold level. Otherwise returns the full resource key. */
  def foldedNodeKey(rid: ResourceId, config: Map[ResourceType, ResourceTypeDisplay]): String =
    foldIndex(rid, config) match {
      case Some(idx) =>
        val segs = effectiveSegments(rid, config).take(idx + 1)
        s"${rid.resourceType.value}:${segs.map((l, v) => s"$l=$v").mkString("/")}"
      case None      => rid.key
    }

  /** Label for the folded node. If folding is active, returns the value at the fold level. Otherwise returns the leaf label. */
  def foldedNodeLabel(rid: ResourceId, config: Map[ResourceType, ResourceTypeDisplay]): String =
    foldIndex(rid, config) match {
      case Some(idx) => effectiveSegments(rid, config)(idx)._2
      case None      => rid.label
    }

  /** Resolve a segment value to its display label, falling back to the value itself. */
  def displayLabel(value: String, segmentLabels: Map[String, String]): String =
    segmentLabels.getOrElse(value, value)

  /** Segments above the visible node (fold level or leaf). These become nested subgraphs/compound nodes in renderers. */
  def containerSegments(rid: ResourceId, config: Map[ResourceType, ResourceTypeDisplay]): List[(String, String)] = {
    val segs = effectiveSegments(rid, config)
    foldIndex(rid, config) match {
      case Some(idx) => segs.take(idx)
      case None      => if (segs.size > 1) segs.init else Nil
    }
  }
}

/** Configuration for class-level diagram rendering.
  *
  * Note: to hide classes (remove them while reconnecting callers → callees), use `LineageAdjustments.builder.cls[T].remove` instead — this operates
  * at the data level so it works for all diagram types.
  */
case class ClassLevelConfig(
    resourceDisplay: Map[ResourceType, ResourceTypeDisplay] = ResourceDisplay.defaults,
    classGrouping: ClassGrouping = ClassGrouping.NoGrouping,
)

object ClassLevelConfig {
  class Builder {
    private var _resourceDisplay: Map[ResourceType, ResourceTypeDisplay] = ResourceDisplay.defaults
    private var _classGrouping: ClassGrouping                            = ClassGrouping.NoGrouping

    def resourceDisplay(config: Map[ResourceType, ResourceTypeDisplay]): Builder = { _resourceDisplay = config; this }
    def groupByPackage(scanBase: String): Builder                                = { _classGrouping = ClassGrouping.ByPackage(scanBase); this }
    def groupClassesBy(fn: ScannedClass => Option[String]): Builder              = { _classGrouping = ClassGrouping.Custom(fn); this }
    def build: ClassLevelConfig                                                  = ClassLevelConfig(_resourceDisplay, _classGrouping)
  }
  def builder: Builder = new Builder
}

// ── Views ────────────────────────────────────────────────────────────────────

/** A named view definition that controls which classes are hidden in the viewer.
  *
  * Views can be defined in code (via `.show` adjustments) or in the UI. The same model is used everywhere — code-defined views appear as pre-defined
  * options alongside user-created views.
  */
case class ViewDefinition(
    name: String,
    hiddenClasses: Set[(String, String)], // (packageName, className) pairs
)

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

/** Complete lineage result — all classes, call graph, and lineage chains.
  *
  * `integrations` contains code-scanned integrations (from TASTy scanners) that drive class nodes and edges in diagrams. `resourceOnlyIntegrations`
  * contains discoveries from resource scanners (e.g., Flyway) that contribute to resource deduplication but do not create class nodes or edges.
  */
case class ScanResult(
    classes: List[ScannedClass],
    callGraph: List[CallEdge],
    integrations: List[DiscoveredIntegration],
    lineageChains: List[LineageChain],
    classDisplayNames: Map[(String, String), String] = Map.empty,
    classGroups: Map[(String, String), String] = Map.empty,
    resourceOnlyIntegrations: List[DiscoveredIntegration] = Nil,
    resourceDependencies: List[ResourceDependency] = Nil,
    views: List[ViewDefinition] = Nil,
    segmentLabels: Map[String, String] = Map.empty,
) {
  lazy val allMethods: List[ScannedMethod]     = classes.flatMap(_.methods)
  lazy val resources: List[DiscoveredResource] = DiscoveredResource.merge(integrations ++ resourceOnlyIntegrations)

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
        for (i    <- m.integrations)
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
