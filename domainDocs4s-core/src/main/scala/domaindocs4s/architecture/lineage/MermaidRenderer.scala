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
    val integrationsByGroup = result.integrations
      .map(i => (i.target, i.integrationType, i.group))
      .distinct
      .groupBy(_._3)

    // Grouped targets in subgraphs
    for (case (Some(groupName), entries) <- integrationsByGroup) {
      // Disambiguate from class subgraphs that share the same name
      val safeId = extGroupNodeId(groupName)
      val label = if (classNames.contains(groupName)) s"$groupName (ext)" else groupName
      sb.append(s"""  subgraph $safeId ["$label"]\n""")
      for ((target, itype, _) <- entries) {
        renderTargetNode(sb, targetNodeId(target), target.split("/").last, itype, indent = "    ")
      }
      sb.append("  end\n")
    }

    // Ungrouped targets as standalone nodes
    for ((target, itype, _) <- integrationsByGroup.getOrElse(None, Nil)) {
      renderTargetNode(sb, targetNodeId(target), target, itype, indent = "  ")
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
      val methodNode = nodeId(i.method)
      val targetNode = targetNodeId(i.target)
      (i.accessType, dataFlow) match {
        case (DataAccessType.Read, false) => sb.append(s"""  $methodNode -.->|Read| $targetNode\n""")
        case (DataAccessType.Read, true)  => sb.append(s"""  $targetNode -.->|Read| $methodNode\n""")
        case (DataAccessType.Write, _)    => sb.append(s"""  $methodNode ==>|Write| $targetNode\n""")
        case _                            => sb.append(s"""  $methodNode -->|${i.accessType}| $targetNode\n""")
      }
    }

    // Styling
    sb.append("\n")
    sb.append("  classDef readNode fill:#d4edda,stroke:#28a745\n")
    sb.append("  classDef writeNode fill:#f8d7da,stroke:#dc3545\n")
    sb.append("  classDef rwNode fill:#fff3cd,stroke:#ffc107\n")
    sb.append("  classDef dbNode fill:#d1ecf1,stroke:#17a2b8\n")
    sb.append("  classDef grpcNode fill:#e8daef,stroke:#8e44ad\n")
    sb.append("  classDef kafkaNode fill:#d5f5e3,stroke:#27ae60\n")
    sb.append("  classDef journalNode fill:#fce4ec,stroke:#e91e63\n")

    for (m <- result.allMethods if m.effectiveAccess != DataAccessType.Pure) {
      val cls = m.effectiveAccess match {
        case DataAccessType.Read      => "readNode"
        case DataAccessType.Write     => "writeNode"
        case DataAccessType.ReadWrite => "rwNode"
        case _                        => ""
      }
      if (cls.nonEmpty) sb.append(s"  class ${nodeId(m.ref)} $cls\n")
    }

    for ((_, entries) <- integrationsByGroup; (target, itype, _) <- entries) {
      sb.append(s"  class ${targetNodeId(target)} ${integrationStyle(itype)}\n")
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
    val hidden      = config.hiddenClasses
    sb.append("flowchart LR\n")

    val classNames = result.classes.map(_.name).toSet

    // Build promoted-callers map: for each hidden class, find non-hidden classes that call it
    val promotedCallers = buildPromotedCallers(hidden, result.callGraph)

    // Class nodes — one node per visible class, optionally grouped into subgraphs
    val groupFn: ScannedClass => Option[String] = config.classGrouping match {
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

    val visibleClasses = result.classes.filter { cls =>
      !hidden.contains(cls.name) &&
        cls.methods.exists(m => m.calls.nonEmpty || m.integrations.nonEmpty || isCalledByOthers(m.ref, result))
    }

    val grouped = visibleClasses.groupBy(groupFn)

    for (case (Some(groupName), classes) <- grouped) {
      val gid = packageGroupId(groupName)
      sb.append(s"""  subgraph $gid ["$groupName"]\n""")
      for (cls <- classes) {
        sb.append(s"""    ${classNodeId(cls.name)}["${cls.name}"]\n""")
      }
      sb.append("  end\n")
    }

    for (cls <- grouped.getOrElse(None, Nil)) {
      sb.append(s"""  ${classNodeId(cls.name)}["${cls.name}"]\n""")
    }

    sb.append("\n")

    // Split integrations into folded vs non-folded
    val allTargets = result.integrations
      .map(i => (i.target, i.integrationType, i.group))
      .distinct

    val (foldedRaw, nonFoldedRaw) = allTargets.partition { case (_, itype, group) =>
      foldByGroup.contains(itype) && group.isDefined
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

    // Class-to-class call graph edges (deduplicated, with transitive edges through hidden classes)
    val rawClassEdges = result.callGraph
      .map(e => (e.caller.className, e.callee.className))
      .distinct

    val visibleEdges = resolveCallEdges(rawClassEdges, hidden)
    for ((from, to) <- visibleEdges) {
      sb.append(s"  ${classNodeId(from)} --> ${classNodeId(to)}\n")
    }

    sb.append("\n")

    // Re-attribute integrations: promote hidden class integrations to their callers
    val effectiveIntegrations = result.integrations.flatMap { i =>
      if (!hidden.contains(i.method.className)) List(i)
      else promotedCallers.getOrElse(i.method.className, Set.empty).toList.map(caller =>
        i.copy(method = i.method.copy(className = caller))
      )
    }

    // Integration edges — folded types: one edge per (className, group)
    val foldedEdges = effectiveIntegrations
      .filter(i => foldByGroup.contains(i.integrationType) && i.group.isDefined)
      .groupBy(i => (i.method.className, i.group.get))
      .map { case ((className, groupName), integrations) =>
        val combined = DataAccessType.combineAll(integrations.map(_.accessType).toList)
        (className, groupName, combined)
      }

    for ((className, groupName, accessType) <- foldedEdges) {
      renderEdge(sb, classNodeId(className), foldedGroupNodeId(groupName), accessType, dataFlow)
    }

    // Integration edges — non-folded types: one edge per (className, target)
    val nonFoldedEdges = effectiveIntegrations
      .filterNot(i => foldByGroup.contains(i.integrationType) && i.group.isDefined)
      .groupBy(i => (i.method.className, i.target))
      .map { case ((className, target), integrations) =>
        val combined = DataAccessType.combineAll(integrations.map(_.accessType).toList)
        (className, target, combined)
      }

    for ((className, target, accessType) <- nonFoldedEdges) {
      renderEdge(sb, classNodeId(className), targetNodeId(target), accessType, dataFlow)
    }

    // Styling
    sb.append("\n")
    sb.append("  classDef readNode fill:#d4edda,stroke:#28a745\n")
    sb.append("  classDef writeNode fill:#f8d7da,stroke:#dc3545\n")
    sb.append("  classDef rwNode fill:#fff3cd,stroke:#ffc107\n")
    sb.append("  classDef dbNode fill:#d1ecf1,stroke:#17a2b8\n")
    sb.append("  classDef grpcNode fill:#e8daef,stroke:#8e44ad\n")
    sb.append("  classDef kafkaNode fill:#d5f5e3,stroke:#27ae60\n")
    sb.append("  classDef journalNode fill:#fce4ec,stroke:#e91e63\n")

    for (cls <- visibleClasses) {
      val style = cls.effectiveAccess match {
        case DataAccessType.Read      => "readNode"
        case DataAccessType.Write     => "writeNode"
        case DataAccessType.ReadWrite => "rwNode"
        case _                        => ""
      }
      if (style.nonEmpty) sb.append(s"  class ${classNodeId(cls.name)} $style\n")
    }

    for ((groupName, itype) <- foldedGroups) {
      val style = integrationStyle(itype)
      sb.append(s"  class ${foldedGroupNodeId(groupName)} $style\n")
    }

    for ((_, entries) <- nonFoldedByGroup; (target, itype, _) <- entries) {
      val style = integrationStyle(itype)
      sb.append(s"  class ${targetNodeId(target)} $style\n")
    }

    sb.toString()
  }

  /** For each hidden class, find the set of non-hidden classes that call it (transitively). */
  private def buildPromotedCallers(hidden: Set[String], callGraph: List[CallEdge]): Map[String, Set[String]] = {
    val callers = callGraph
      .map(e => (e.caller.className, e.callee.className))
      .distinct
      .groupBy(_._2)
      .view.mapValues(_.map(_._1).toSet).toMap

    def findNonHidden(cls: String, visited: Set[String]): Set[String] = {
      if (visited.contains(cls)) Set.empty
      else callers.getOrElse(cls, Set.empty).flatMap { c =>
        if (!hidden.contains(c)) Set(c)
        else findNonHidden(c, visited + cls)
      }
    }

    hidden.map(h => h -> findNonHidden(h, Set.empty)).toMap
  }

  /** Resolve call edges: remove hidden classes, add transitive edges through them. */
  private def resolveCallEdges(edges: List[(String, String)], hidden: Set[String]): List[(String, String)] = {
    val callees = edges.groupBy(_._1).view.mapValues(_.map(_._2).toSet).toMap

    def resolveTarget(target: String, visited: Set[String]): Set[String] = {
      if (visited.contains(target)) Set.empty
      else if (!hidden.contains(target)) Set(target)
      else callees.getOrElse(target, Set.empty).flatMap(t => resolveTarget(t, visited + target))
    }

    edges
      .filter { case (from, _) => !hidden.contains(from) }
      .flatMap { case (from, to) => resolveTarget(to, Set.empty).map(t => (from, t)) }
      .distinct
  }

  /** Render a single integration target node into the StringBuilder. */
  private def renderTargetNode(sb: StringBuilder, id: String, label: String, itype: String, indent: String): Unit =
    itype match {
      case "grpc"          => sb.append(s"""$indent$id{{"${label}\n[$itype]"}}\n""")
      case "kafka"         => sb.append(s"""$indent$id(["${label}\n[$itype]"])\n""")
      case "pekko-journal" => sb.append(s"""$indent$id[["${label}\n[$itype]"]]\n""")
      case _               => sb.append(s"""$indent$id[("${label}\n[$itype]")]\n""")
    }

  private def renderEdge(sb: StringBuilder, from: String, to: String, accessType: DataAccessType, dataFlow: Boolean): Unit =
    (accessType, dataFlow) match {
      case (DataAccessType.Read, false) => sb.append(s"""  $from -.->|Read| $to\n""")
      case (DataAccessType.Read, true)  => sb.append(s"""  $to -.->|Read| $from\n""")
      case (DataAccessType.Write, _)    => sb.append(s"""  $from ==>|Write| $to\n""")
      case (DataAccessType.ReadWrite, _) => sb.append(s"""  $from -->|ReadWrite| $to\n""")
      case _                            => sb.append(s"""  $from -->|$accessType| $to\n""")
    }

  private def integrationStyle(itype: String): String = itype match {
    case "grpc"          => "grpcNode"
    case "kafka"         => "kafkaNode"
    case "pekko-journal" => "journalNode"
    case _               => "dbNode"
  }

  def toViewUrl(mermaidCode: String): String = {
    val json    = s"""{"code":${escapeJsonString(mermaidCode)}}"""
    val encoded = Base64.getEncoder.encodeToString(json.getBytes(StandardCharsets.UTF_8))
    val base64url = encoded
      .replace('+', '-')
      .replace('/', '_')
    s"https://mermaid.live/edit#base64:$base64url"
  }

  private def sanitizeId(raw: String): String =
    raw.replaceAll("[^a-zA-Z0-9_]", "_")

  private def nodeId(ref: MethodRef): String =
    sanitizeId(s"${ref.className}_${ref.methodName}")

  private def targetNodeId(target: String): String =
    s"ext_${sanitizeId(target)}"

  private def classNodeId(className: String): String =
    s"cls_${sanitizeId(className)}"

  private def foldedGroupNodeId(groupName: String): String =
    s"fold_${sanitizeId(groupName)}"

  private def packageGroupId(groupName: String): String =
    s"pkg_${sanitizeId(groupName)}"

  private def extGroupNodeId(groupName: String): String =
    s"ext_group_${sanitizeId(groupName)}"

  private def isCalledByOthers(ref: MethodRef, result: ScanResult): Boolean =
    result.callGraph.exists(_.callee == ref)

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
