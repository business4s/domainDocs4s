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
    sb.append("%%{init: {'flowchart': {'defaultRenderer': 'elk'}}}%%\n")
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

    // Resource nodes — use full segment hierarchy (no folding at method level)
    val config = ResourceDisplay.defaults
    renderResourceHierarchy(sb, result.resources, config, classNames, result.segmentLabels, indent = "  ")

    sb.append("\n")

    // Call graph edges
    for (edge <- result.callGraph) {
      val from = nodeId(edge.caller)
      val to   = nodeId(edge.callee)
      sb.append(s"  $from --> $to\n")
    }

    sb.append("\n")

    // Integration edges — method level uses full resource key (no folding)
    for (i <- result.integrations) {
      renderEdge(sb, nodeId(i.method), targetNodeId(i.resourceId.key), i.accessType, dataFlow)
    }

    // Resource dependency edges
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

    for (res <- result.resources) {
      sb.append(s"  class ${targetNodeId(res.resourceId.key)} ${integrationStyle(res.resourceType)}\n")
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
    val dispConfig = config.resourceDisplay
    sb.append("%%{init: {'flowchart': {'defaultRenderer': 'elk'}}}%%\n")
    sb.append("flowchart LR\n")

    val classNames = result.classes.map(_.name).toSet

    // Class nodes — one node per visible class, optionally grouped into subgraphs
    val configGroupFn: ScannedClass => Option[String] = config.classGrouping match {
      case ClassGrouping.NoGrouping      => _ => None
      case ClassGrouping.ByPackage(base) =>
        val prefix = if (base.endsWith(".")) base else base + "."
        cls => {
          val rest = if (cls.packageName.startsWith(prefix)) cls.packageName.drop(prefix.length) else ""
          val seg  = rest.takeWhile(_ != '.')
          if (seg.isEmpty) None else Some(seg)
        }
      case ClassGrouping.Custom(fn)      => fn
    }
    val groupFn: ScannedClass => Option[String] = cls => result.classGroups.get((cls.packageName, cls.name)).orElse(configGroupFn(cls))

    val visibleFromCallGraph = result.classes.filter { cls =>
      cls.methods.exists(m => m.calls.nonEmpty || m.integrations.nonEmpty || isCalledByOthers(m.ref, result))
    }

    val knownClassKeys: Set[ClassKey]              = result.classes.map(cls => (cls.packageName, cls.name)).toSet
    val integrationOnlyClasses: List[ScannedClass] = result.integrations
      .map(i => (i.method.packageName, i.method.className))
      .distinct
      .filterNot(k => knownClassKeys.contains(k))
      .map { case (pkg, name) => ScannedClass(name = name, packageName = pkg, methods = Nil) }

    val visibleClasses = visibleFromCallGraph ++ integrationOnlyClasses

    val displayName: ScannedClass => String = cls => result.classDisplayNames.getOrElse((cls.packageName, cls.name), cls.name)

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

    // Resource rendering — generic algorithm driven by ResourceDisplay config
    // For each resource, compute: container segments (become subgraphs), visible node (at fold level or leaf)
    // Resources that share the same foldedNodeKey are collapsed into one node.

    val segLabels = result.segmentLabels
    val foldedResources: Seq[FoldedResource] = result.resources.map { res =>
      FoldedResource(
        nodeKey = ResourceDisplay.foldedNodeKey(res.resourceId, dispConfig),
        nodeLabel = ResourceDisplay.displayLabel(ResourceDisplay.foldedNodeLabel(res.resourceId, dispConfig), segLabels),
        rtype = res.resourceType,
        containers = ResourceDisplay.containerSegments(res.resourceId, dispConfig).map((l, v) => (l, ResourceDisplay.displayLabel(v, segLabels))),
      )
    }.distinctBy(_.nodeKey)

    // Build nested subgraph structure from container segments
    renderFoldedResourceHierarchy(sb, foldedResources, classNames, indent = "  ")

    sb.append("\n")

    // Class-to-class call graph edges (deduplicated)
    val declaredClassKeys: Set[ClassKey]       = visibleClasses.map(cls => (cls.packageName, cls.name)).toSet
    val classEdges: List[(ClassKey, ClassKey)] = result.callGraph
      .map(e => ((e.caller.packageName, e.caller.className), (e.callee.packageName, e.callee.className)))
      .distinct
      .filter { case (from, to) => from != to && declaredClassKeys.contains(from) && declaredClassKeys.contains(to) }
    for ((from, to) <- classEdges) {
      sb.append(s"  ${classNodeId(from._1, from._2)} --> ${classNodeId(to._1, to._2)}\n")
    }

    sb.append("\n")

    // Integration edges — resolve to folded node key
    val integrationEdges = result.integrations
      .groupBy(i => ((i.method.packageName, i.method.className), ResourceDisplay.foldedNodeKey(i.resourceId, dispConfig)))
      .map { case ((classKey, foldedKey), integrations) =>
        val combined = DataAccessType.combineAll(integrations.map(_.accessType).toList)
        (classKey, foldedKey, combined)
      }

    for ((classKey, foldedKey, accessType) <- integrationEdges) {
      renderEdge(sb, classNodeId(classKey._1, classKey._2), targetNodeId(foldedKey), accessType, dataFlow)
    }

    // Resource dependency edges
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

    for (fr <- foldedResources) {
      sb.append(s"  class ${targetNodeId(fr.nodeKey)} ${integrationStyle(fr.rtype)}\n")
    }

    sb.toString()
  }

  /** Render resource hierarchy using full segments (no folding) — used at method level. */
  private def renderResourceHierarchy(
      sb: StringBuilder,
      resources: List[DiscoveredResource],
      config: Map[ResourceType, ResourceTypeDisplay],
      classNames: Set[String],
      segmentLabels: Map[String, String],
      indent: String,
  ): Unit = {
    case class ResEntry(key: String, label: String, rtype: ResourceType, containers: List[(String, String)])

    val entries = resources.map { res =>
      ResEntry(
        key = res.resourceId.key,
        label = ResourceDisplay.displayLabel(res.target, segmentLabels),
        rtype = res.resourceType,
        containers = ResourceDisplay.containerSegments(res.resourceId, config).map((l, v) => (l, ResourceDisplay.displayLabel(v, segmentLabels))),
      )
    }

    renderNestedSubgraphs(sb, entries.map(e => (e.containers, e)), classNames, indent) { (sb, entry, ind) =>
      renderTargetNode(sb, targetNodeId(entry.key), entry.label, entry.rtype, indent = ind)
    }
  }

  /** Render folded resource hierarchy — used at class level. */
  private case class FoldedResource(
      nodeKey: String,
      nodeLabel: String,
      rtype: ResourceType,
      containers: List[(String, String)],
  )

  private def renderFoldedResourceHierarchy(
      sb: StringBuilder,
      resources: Seq[FoldedResource],
      classNames: Set[String],
      indent: String,
  ): Unit = {
    renderNestedSubgraphs(sb, resources.map(r => (r.containers, r)), classNames, indent) { (sb, fr, ind) =>
      val label = if (classNames.contains(fr.nodeLabel)) s"${fr.nodeLabel} (ext)" else fr.nodeLabel
      renderTargetNode(sb, targetNodeId(fr.nodeKey), label, fr.rtype, indent = ind)
    }
  }

  /** Generic nested subgraph renderer. Groups entries by their container prefix and creates Mermaid subgraphs.
    *
    * Each entry has a `containers` list of (level, value) pairs. Entries sharing the same first container segment are grouped under one subgraph, then
    * recursively grouped by subsequent segments.
    */
  private def renderNestedSubgraphs[A](
      sb: StringBuilder,
      entries: Seq[(List[(String, String)], A)],
      classNames: Set[String],
      indent: String,
  )(renderLeaf: (StringBuilder, A, String) => Unit): Unit = {
    // Separate entries with no more containers (render as leaf nodes) from those with containers
    val (leaves, nested) = entries.partition(_._1.isEmpty)

    // Render leaf nodes
    for ((_, entry) <- leaves) {
      renderLeaf(sb, entry, indent)
    }

    // Group by first container segment and recurse
    val byFirstContainer = nested.groupBy(_._1.head)
    for (((level, value), group) <- byFirstContainer) {
      val sgId  = extGroupNodeId(s"${level}_$value")
      val label = if (classNames.contains(value)) s"$value (ext)" else value
      sb.append(s"""${indent}subgraph $sgId ["$label"]\n""")
      val remaining = group.map { case (containers, entry) => (containers.tail, entry) }
      renderNestedSubgraphs(sb, remaining, classNames, indent + "  ")(renderLeaf)
      sb.append(s"${indent}end\n")
    }
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

  private def renderTargetNode(sb: StringBuilder, id: String, label: String, rtype: ResourceType, indent: String): Unit = {
    val safe = escapeHtmlChars(label)
    rtype match {
      case ResourceType.Grpc  => sb.append(s"""$indent$id{{"$safe"}}\n""")
      case ResourceType.Kafka => sb.append(s"""$indent$id(["$safe"])\n""")
      case ResourceType.S3    => sb.append(s"""$indent$id[/"$safe"/]\n""")
      case _                  => sb.append(s"""$indent$id[("$safe")]\n""")
    }
  }

  private def renderResourceDependencies(sb: StringBuilder, result: ScanResult, dataFlow: Boolean): Unit = {
    val knownKeys = result.resources.map(_.resourceId.key).toSet
    for (dep <- result.resourceDependencies if knownKeys(dep.from.key) && knownKeys(dep.to.key)) {
      val fromNode = targetNodeId(dep.from.key)
      val toNode   = targetNodeId(dep.to.key)
      if (dataFlow) sb.append(s"""  $fromNode -.-> $toNode\n""")
      else sb.append(s"""  $toNode -.-> $fromNode\n""")
    }
  }

  private def renderEdge(sb: StringBuilder, from: String, to: String, accessType: DataAccessType, dataFlow: Boolean): Unit =
    (accessType, dataFlow) match {
      case (DataAccessType.Read, false)  => sb.append(s"""  $from -.->|Read| $to\n""")
      case (DataAccessType.Read, true)   => sb.append(s"""  $to -.->|Read| $from\n""")
      case (DataAccessType.Write, _)     => sb.append(s"""  $from ==>|Write| $to\n""")
      case (DataAccessType.ReadWrite, _) => sb.append(s"""  $from -->|ReadWrite| $to\n""")
      case _                             => sb.append(s"""  $from -->|$accessType| $to\n""")
    }

  private def integrationStyle(rtype: ResourceType): String = rtype match {
    case ResourceType.Grpc  => "grpcNode"
    case ResourceType.Kafka => "kafkaNode"
    case ResourceType.S3    => "s3Node"
    case _                  => "dbNode"
  }

  def toViewUrl(mermaidCode: String): String = {
    val json      = s"""{"code":${escapeJsonString(mermaidCode)}}"""
    val encoded   = Base64.getEncoder.encodeToString(json.getBytes(StandardCharsets.UTF_8))
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
