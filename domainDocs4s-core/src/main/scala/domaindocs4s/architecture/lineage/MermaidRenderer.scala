package domaindocs4s.architecture.lineage

import java.nio.charset.StandardCharsets
import java.util.Base64

object MermaidRenderer {

  /** Render with arrows following call/access direction (method → target for both reads and writes). */
  def render(result: ScanResult): String =
    renderInternal(result, dataFlow = false)

  /** Render with arrows following data flow (target → method for reads, method → target for writes). */
  def renderDataFlow(result: ScanResult): String =
    renderInternal(result, dataFlow = true)

  private def renderInternal(result: ScanResult, dataFlow: Boolean): String = {
    val sb = new StringBuilder
    sb.append("flowchart LR\n")

    // Class subgraphs with methods
    val classNames = result.classes.map(_.name).toSet
    for (cls <- result.classes) {
      val methods = cls.methods.filter(m => m.calls.nonEmpty || m.integrations.nonEmpty || isCalledByOthers(m.ref, result))
      if (methods.nonEmpty) {
        sb.append(s"  subgraph ${cls.name}\n")
        for (m <- methods) {
          val id = nodeId(m.ref)
          sb.append(s"""    $id["${m.ref.methodName}"]\n""")
        }
        sb.append("  end\n")
      }
    }

    sb.append("\n")

    // Integration target nodes — grouped by subgraph when group is set
    val resourcesByGroup = result.resources
      .map(r => (r.target, r.resourceType, r.group))
      .groupBy(_._3)

    // Grouped targets in subgraphs
    for (case (Some(groupName), entries) <- resourcesByGroup) {
      // Disambiguate from class subgraphs that share the same name
      val safeId = extGroupNodeId(groupName)
      val label = if (classNames.contains(groupName)) s"$groupName (ext)" else groupName
      sb.append(s"""  subgraph $safeId ["$label"]\n""")
      for ((target, rtype, _) <- entries) {
        renderTargetNode(sb, targetNodeId(target), target.split("/").last, rtype, indent = "    ")
      }
      sb.append("  end\n")
    }

    // Ungrouped targets as standalone nodes
    for ((target, rtype, _) <- resourcesByGroup.getOrElse(None, Nil)) {
      renderTargetNode(sb, targetNodeId(target), target, rtype, indent = "  ")
    }

    sb.append("\n")

    // Call graph edges
    for (edge <- result.callGraph) {
      val from = nodeId(edge.caller)
      val to = nodeId(edge.callee)
      sb.append(s"  $from --> $to\n")
    }

    sb.append("\n")

    // Integration edges
    for (i <- result.integrations) {
      renderEdge(sb, nodeId(i.method), targetNodeId(i.target), i.accessType, dataFlow)
    }

    // Resource dependency edges (e.g., VIEW sources)
    renderResourceDependencies(sb, result, dataFlow)

    // Styling
    sb.append("\n")
    appendStyleDefs(sb)

    for (m <- result.allMethods if m.effectiveAccess != DataAccessType.Pure) {
      val cls = m.effectiveAccess match {
        case DataAccessType.Read      => "readNode"
        case DataAccessType.Write     => "writeNode"
        case DataAccessType.ReadWrite => "rwNode"
        case _                        => ""
      }
      if (cls.nonEmpty) sb.append(s"  class ${nodeId(m.ref)} $cls\n")
    }

    for ((_, entries) <- resourcesByGroup; (target, rtype, _) <- entries) {
      sb.append(s"  class ${targetNodeId(target)} ${integrationStyle(rtype)}\n")
    }

    sb.toString()
  }

  /** Render class-level diagram with arrows following call/access direction. */
  def renderClassLevel(result: ScanResult, config: ClassLevelConfig = ClassLevelConfig()): String =
    renderClassLevelInternal(result, dataFlow = false, config)

  /** Render class-level diagram with arrows following data flow. */
  def renderClassLevelDataFlow(result: ScanResult, config: ClassLevelConfig = ClassLevelConfig()): String =
    renderClassLevelInternal(result, dataFlow = true, config)

  private def renderClassLevelInternal(result: ScanResult, dataFlow: Boolean, config: ClassLevelConfig): String = {
    val sb         = new StringBuilder
    val foldByGroup = config.foldByGroup
    sb.append("flowchart LR\n")

    val classNames = result.classes.map(_.name).toSet

    // Class nodes — one node per visible class, optionally grouped into subgraphs
    // classGroups (from adjustments) override config-based grouping per class
    val configGroupFn: ScannedClass => Option[String] = config.classGrouping match {
      case ClassGrouping.NoGrouping => _ => None
      case ClassGrouping.ByPackage(base) =>
        val prefix = if (base.endsWith(".")) base else base + "."
        cls => {
          val rest = if (cls.packageName.startsWith(prefix)) cls.packageName.drop(prefix.length) else ""
          val seg = rest.takeWhile(_ != '.')
          if (seg.isEmpty) None else Some(seg)
        }
      case ClassGrouping.Custom(fn) => fn
    }
    val groupFn: ScannedClass => Option[String] = cls =>
      result.classGroups.get((cls.packageName, cls.name)).orElse(configGroupFn(cls))

    val visibleFromCallGraph = result.classes.filter { cls =>
      cls.methods.exists(m => m.calls.nonEmpty || m.integrations.nonEmpty || isCalledByOthers(m.ref, result))
    }

    // Classes from integrations not in result.classes (e.g., Scala objects detected by scanners)
    val knownClassKeys: Set[ClassKey] = result.classes.map(cls => (cls.packageName, cls.name)).toSet
    val integrationOnlyClasses: List[ScannedClass] = result.integrations
      .map(i => (i.method.packageName, i.method.className))
      .distinct
      .filterNot(k => knownClassKeys.contains(k))
      .map { case (pkg, name) => ScannedClass(name = name, packageName = pkg, methods = Nil) }

    val visibleClasses = visibleFromCallGraph ++ integrationOnlyClasses

    val displayName: ScannedClass => String = cls =>
      result.classDisplayNames.getOrElse((cls.packageName, cls.name), cls.name)

    val grouped = visibleClasses.groupBy(groupFn)

    for (case (Some(groupName), classes) <- grouped) {
      val gid = packageGroupId(groupName)
      sb.append(s"""  subgraph $gid ["$groupName"]\n""")
      for (cls <- classes) {
        sb.append(s"""    ${classNodeId(cls.packageName, cls.name)}["${displayName(cls)}"]\n""")
      }
      sb.append("  end\n")
    }

    for (cls <- grouped.getOrElse(None, Nil)) {
      sb.append(s"""  ${classNodeId(cls.packageName, cls.name)}["${displayName(cls)}"]\n""")
    }

    sb.append("\n")

    // Split resources into folded vs non-folded
    val allTargets = result.resources
      .map(r => (r.target, r.resourceType, r.group))

    val (foldedRaw, nonFoldedRaw) = allTargets.partition { case (_, rtype, group) =>
      foldByGroup.contains(rtype) && group.isDefined
    }

    // Folded integration nodes — one standalone node per group
    val foldedGroups = foldedRaw
      .groupBy(_._3.get)
      .map { case (groupName, entries) => (groupName, entries.head._2) }

    for ((groupName, itype) <- foldedGroups) {
      val id    = foldedGroupNodeId(groupName)
      val label = if (classNames.contains(groupName)) s"$groupName (ext)" else groupName
      renderTargetNode(sb, id, label, itype, indent = "  ")
    }

    // Non-folded integration nodes — grouped into subgraphs or standalone
    val nonFoldedByGroup = nonFoldedRaw.groupBy(_._3)

    for (case (Some(groupName), entries) <- nonFoldedByGroup) {
      val safeId = extGroupNodeId(groupName)
      val label  = if (classNames.contains(groupName)) s"$groupName (ext)" else groupName
      sb.append(s"""  subgraph $safeId ["$label"]\n""")
      for ((target, itype, _) <- entries) {
        renderTargetNode(sb, targetNodeId(target), target.split("/").last, itype, indent = "    ")
      }
      sb.append("  end\n")
    }

    for ((target, itype, _) <- nonFoldedByGroup.getOrElse(None, Nil)) {
      renderTargetNode(sb, targetNodeId(target), target, itype, indent = "  ")
    }

    sb.append("\n")

    // Class-to-class call graph edges (deduplicated)
    val declaredClassKeys: Set[ClassKey] = visibleClasses.map(cls => (cls.packageName, cls.name)).toSet
    val classEdges: List[(ClassKey, ClassKey)] = result.callGraph
      .map(e => ((e.caller.packageName, e.caller.className), (e.callee.packageName, e.callee.className)))
      .distinct
      .filter { case (from, to) => from != to && declaredClassKeys.contains(from) && declaredClassKeys.contains(to) }
    for ((from, to) <- classEdges) {
      sb.append(s"  ${classNodeId(from._1, from._2)} --> ${classNodeId(to._1, to._2)}\n")
    }

    sb.append("\n")

    // Integration edges — folded types: one edge per (classKey, group)
    val foldedEdges = result.integrations
      .filter(i => foldByGroup.contains(i.resourceType) && i.group.isDefined)
      .groupBy(i => ((i.method.packageName, i.method.className), i.group.get))
      .map { case ((classKey, groupName), integrations) =>
        val combined = DataAccessType.combineAll(integrations.map(_.accessType).toList)
        (classKey, groupName, combined)
      }

    for ((classKey, groupName, accessType) <- foldedEdges) {
      renderEdge(sb, classNodeId(classKey._1, classKey._2), foldedGroupNodeId(groupName), accessType, dataFlow)
    }

    // Integration edges — non-folded types: one edge per (classKey, target)
    val nonFoldedEdges = result.integrations
      .filterNot(i => foldByGroup.contains(i.resourceType) && i.group.isDefined)
      .groupBy(i => ((i.method.packageName, i.method.className), i.target))
      .map { case ((classKey, target), integrations) =>
        val combined = DataAccessType.combineAll(integrations.map(_.accessType).toList)
        (classKey, target, combined)
      }

    for ((classKey, target, accessType) <- nonFoldedEdges) {
      renderEdge(sb, classNodeId(classKey._1, classKey._2), targetNodeId(target), accessType, dataFlow)
    }

    // Resource dependency edges (e.g., VIEW sources)
    renderResourceDependencies(sb, result, dataFlow)

    // Styling
    sb.append("\n")
    appendStyleDefs(sb)

    for (cls <- visibleClasses) {
      val style = cls.effectiveAccess match {
        case DataAccessType.Read      => "readNode"
        case DataAccessType.Write     => "writeNode"
        case DataAccessType.ReadWrite => "rwNode"
        case _                        => ""
      }
      if (style.nonEmpty) sb.append(s"  class ${classNodeId(cls.packageName, cls.name)} $style\n")
    }

    for ((groupName, rtype) <- foldedGroups) {
      val style = integrationStyle(rtype)
      sb.append(s"  class ${foldedGroupNodeId(groupName)} $style\n")
    }

    for ((_, entries) <- nonFoldedByGroup; (target, rtype, _) <- entries) {
      val style = integrationStyle(rtype)
      sb.append(s"  class ${targetNodeId(target)} $style\n")
    }

    sb.toString()
  }

  private def appendStyleDefs(sb: StringBuilder): Unit = {
    sb.append("  classDef readNode fill:#d4edda,stroke:#28a745\n")
    sb.append("  classDef writeNode fill:#f8d7da,stroke:#dc3545\n")
    sb.append("  classDef rwNode fill:#fff3cd,stroke:#ffc107\n")
    sb.append("  classDef dbNode fill:#d1ecf1,stroke:#17a2b8\n")
    sb.append("  classDef grpcNode fill:#e8daef,stroke:#8e44ad\n")
    sb.append("  classDef kafkaNode fill:#d5f5e3,stroke:#27ae60\n")
    sb.append("  classDef s3Node fill:#ffe0b2,stroke:#fb8c00\n")
  }

  /** Render a single integration target node into the StringBuilder. */
  private def renderTargetNode(sb: StringBuilder, id: String, label: String, rtype: ResourceType, indent: String): Unit = {
    val safe = escapeHtmlChars(label)
    rtype match {
      case ResourceType.Grpc    => sb.append(s"""$indent$id{{"$safe"}}\n""")
      case ResourceType.Kafka   => sb.append(s"""$indent$id(["$safe"])\n""")
      case ResourceType.S3      => sb.append(s"""$indent$id[/"$safe"/]\n""")
      case _                    => sb.append(s"""$indent$id[("$safe")]\n""")
    }
  }

  private def renderResourceDependencies(sb: StringBuilder, result: ScanResult, dataFlow: Boolean): Unit = {
    val knownTargets = result.resources.map(_.target).toSet
    for (dep <- result.resourceDependencies if knownTargets(dep.from) && knownTargets(dep.to)) {
      val fromNode = targetNodeId(dep.from)
      val toNode   = targetNodeId(dep.to)
      if (dataFlow) sb.append(s"""  $fromNode -.-> $toNode\n""")
      else sb.append(s"""  $toNode -.-> $fromNode\n""")
    }
  }

  private def renderEdge(sb: StringBuilder, from: String, to: String, accessType: DataAccessType, dataFlow: Boolean): Unit =
    (accessType, dataFlow) match {
      case (DataAccessType.Read, false) => sb.append(s"""  $from -.->|Read| $to\n""")
      case (DataAccessType.Read, true)  => sb.append(s"""  $to -.->|Read| $from\n""")
      case (DataAccessType.Write, _)    => sb.append(s"""  $from ==>|Write| $to\n""")
      case (DataAccessType.ReadWrite, _) => sb.append(s"""  $from -->|ReadWrite| $to\n""")
      case _                            => sb.append(s"""  $from -->|$accessType| $to\n""")
    }

  private def integrationStyle(rtype: ResourceType): String = rtype match {
    case ResourceType.Grpc    => "grpcNode"
    case ResourceType.Kafka   => "kafkaNode"
    case ResourceType.S3      => "s3Node"
    case _                    => "dbNode"
  }

  def toViewUrl(mermaidCode: String): String = {
    val json    = s"""{"code":${escapeJsonString(mermaidCode)}}"""
    val encoded = Base64.getEncoder.encodeToString(json.getBytes(StandardCharsets.UTF_8))
    val base64url = encoded
      .replace('+', '-')
      .replace('/', '_')
    s"https://mermaid.live/edit#base64:$base64url"
  }

  private type ClassKey = (String, String) // (packageName, className)

  private def hashPkg(pkg: String): String =
    if (pkg.isEmpty) "0"
    else f"${pkg.hashCode.abs}%08x".take(8)

  private def sanitizeId(raw: String): String =
    raw.replaceAll("[^a-zA-Z0-9_]", "_")

  private def nodeId(ref: MethodRef): String =
    s"${hashPkg(ref.packageName)}_${sanitizeId(s"${ref.className}_${ref.methodName}")}"

  private def targetNodeId(target: String): String =
    s"ext_${sanitizeId(target)}"

  private def classNodeId(packageName: String, className: String): String =
    s"cls_${hashPkg(packageName)}_${sanitizeId(className)}"

  private def foldedGroupNodeId(groupName: String): String =
    s"fold_${sanitizeId(groupName)}"

  private def packageGroupId(groupName: String): String =
    s"pkg_${sanitizeId(groupName)}"

  private def extGroupNodeId(groupName: String): String =
    s"ext_group_${sanitizeId(groupName)}"

  private def isCalledByOthers(ref: MethodRef, result: ScanResult): Boolean =
    result.callGraph.exists(_.callee == ref)

  private def escapeHtmlChars(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def escapeJsonString(s: String): String = {
    val sb = new StringBuilder("\"")
    for (c <- s) c match {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case _    => sb.append(c)
    }
    sb.append("\"")
    sb.toString()
  }
}
