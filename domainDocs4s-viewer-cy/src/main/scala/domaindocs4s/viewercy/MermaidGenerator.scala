package domaindocs4s.viewercy

/** Generates Mermaid class-level flowchart markup from LineageData. */
object MermaidGenerator {

  def renderClassLevel(data: LineageData, visibleClassIds: Set[String], dataFlow: Boolean): String = {
    val sb      = new StringBuilder
    val config  = data.resourceDisplayConfig
    val segLabels = data.segmentLabels

    sb.append("%%{init: {'flowchart': {'defaultRenderer': 'elk'}}}%%\n")
    sb.append("flowchart LR\n")

    val visibleClasses = data.classes.filter(c => visibleClassIds.contains(c.classId))
    val classNames     = visibleClasses.map(_.name).toSet

    // Class nodes, grouped by group
    val grouped = visibleClasses.groupBy(_.group)

    for (case (Some(groupName), classes) <- grouped) {
      val gid = sanitizeId(s"pkg_$groupName")
      sb.append(s"""  subgraph $gid ["$groupName"]\n""")
      for (cls <- classes) {
        sb.append(s"""    ${classNodeId(cls)}["${escapeHtml(cls.displayName)}"]\n""")
      }
      sb.append("  end\n")
    }
    for (cls <- grouped.getOrElse(None, Nil)) {
      sb.append(s"""  ${classNodeId(cls)}["${escapeHtml(cls.displayName)}"]\n""")
    }
    sb.append("\n")

    // Resource nodes — folded, with container subgraphs
    case class FoldedRes(nodeKey: String, nodeLabel: String, resourceType: String, containers: List[(String, String)])

    val foldedResources: List[FoldedRes] = data.resources.map { res =>
      val effSegs = effectiveSegments(res.segments, res.resourceType, config)
      val foldIdx = foldIndex(res.segments, res.resourceType, config)
      val (containers, leafSegs) = foldIdx match {
        case Some(idx) =>
          val folded = effSegs.take(idx + 1)
          if (folded.size > 1) (folded.take(1).map(s => (s.level, displayLabel(s.value, segLabels))), folded.tail)
          else (Nil, folded)
        case None =>
          if (effSegs.size > 1) (effSegs.take(1).map(s => (s.level, displayLabel(s.value, segLabels))), effSegs.tail)
          else (Nil, effSegs)
      }
      val label = leafSegs.map(s => displayLabel(s.value, segLabels)).mkString(" / ")
      val key = GraphBuilder.foldedNodeKey(res.segments, res.resourceType, config)
      FoldedRes(key, label, res.resourceType, containers)
    }.distinctBy(_.nodeKey)

    // Render nested subgraphs
    renderNestedSubgraphs(sb, foldedResources.map(fr => (fr.containers, fr)), classNames, "  ") { (sb, fr, indent) =>
      val label = if (classNames.contains(fr.nodeLabel)) s"${fr.nodeLabel} (ext)" else fr.nodeLabel
      renderTargetNode(sb, targetNodeId(fr.nodeKey), escapeHtml(label), fr.resourceType, indent)
    }
    sb.append("\n")

    // Class-to-class call edges
    val classEdges = data.callGraph
      .map(e => (classNodeIdFromRef(e.caller), classNodeIdFromRef(e.callee)))
      .distinct
      .filter { case (from, to) => from != to }
      // Only include edges between visible classes
      .filter { case (from, to) =>
        val visibleNodeIds = visibleClasses.map(classNodeId).toSet
        visibleNodeIds.contains(from) && visibleNodeIds.contains(to)
      }
    for ((from, to) <- classEdges) {
      sb.append(s"  $from --> $to\n")
    }
    sb.append("\n")

    // Integration edges — class to folded resource
    val integrationEdges = data.integrations
      .map { i =>
        val classId = classNodeIdFromRef(i.method)
        val foldKey = GraphBuilder.foldedNodeKey(i.segments, i.resourceType, config)
        (classId, foldKey, i.accessType)
      }
      .groupBy { case (from, to, _) => (from, to) }
      .map { case ((from, to), entries) =>
        val combined = GraphBuilder.combineAccess(entries.map(_._3).toList)
        (from, to, combined)
      }

    for ((from, to, access) <- integrationEdges) {
      renderEdge(sb, from, targetNodeId(to), access, dataFlow)
    }

    // Resource dependency edges
    val knownKeys = data.resources.map(_.key).toSet
    for (dep <- data.resourceDependencies if knownKeys(dep.from) && knownKeys(dep.to)) {
      val fromKey = GraphBuilder.foldedNodeKey(
        data.resources.find(_.key == dep.from).map(_.segments).getOrElse(Nil),
        dep.resourceType,
        config,
      )
      val toKey = GraphBuilder.foldedNodeKey(
        data.resources.find(_.key == dep.to).map(_.segments).getOrElse(Nil),
        dep.resourceType,
        config,
      )
      val fromNode = targetNodeId(fromKey)
      val toNode   = targetNodeId(toKey)
      if (fromNode != toNode) {
        if (dataFlow) sb.append(s"  $fromNode -.-> $toNode\n")
        else sb.append(s"  $toNode -.-> $fromNode\n")
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
    sb.append("  classDef s3Node fill:#ffe0b2,stroke:#fb8c00\n")

    for (cls <- visibleClasses) {
      val style = cls.effectiveAccess match {
        case "Read"      => "readNode"
        case "Write"     => "writeNode"
        case "ReadWrite" => "rwNode"
        case _           => ""
      }
      if (style.nonEmpty) sb.append(s"  class ${classNodeId(cls)} $style\n")
    }

    for (fr <- foldedResources) {
      sb.append(s"  class ${targetNodeId(fr.nodeKey)} ${integrationStyle(fr.resourceType)}\n")
    }

    sb.toString()
  }

  // ── Helpers ──

  private def effectiveSegments(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig]): List[Segment] =
    config.get(resourceType).flatMap(_.containerLabel) match {
      case Some(label) => Segment("container", label) :: segments
      case None        => segments
    }

  private def foldIndex(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig]): Option[Int] =
    config.get(resourceType).flatMap(_.foldAtLevel).flatMap { level =>
      val segs = effectiveSegments(segments, resourceType, config)
      val idx  = segs.indexWhere(_.level == level)
      if (idx >= 0) Some(idx) else None
    }

  private def displayLabel(value: String, segLabels: Map[String, String]): String =
    segLabels.getOrElse(value, value)

  private def classNodeId(cls: ClassInfo): String =
    s"cls_${cls.packageName.hashCode.abs}_${sanitizeId(cls.name)}"

  private def classNodeIdFromRef(ref: MethodRef): String =
    s"cls_${ref.packageName.hashCode.abs}_${sanitizeId(ref.className)}"

  private def targetNodeId(key: String): String =
    s"ext_${sanitizeId(key)}"

  private def sanitizeId(raw: String): String =
    raw.replaceAll("[^a-zA-Z0-9_]", "_")

  private def escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def renderTargetNode(sb: StringBuilder, id: String, label: String, rtype: String, indent: String): Unit =
    rtype match {
      case "grpc"  => sb.append(s"""$indent$id{{"$label"}}\n""")
      case "kafka" => sb.append(s"""$indent$id(["$label"])\n""")
      case "s3"    => sb.append(s"""$indent$id[/"$label"/]\n""")
      case _       => sb.append(s"""$indent$id[("$label")]\n""")
    }

  private def renderEdge(sb: StringBuilder, from: String, to: String, access: String, dataFlow: Boolean): Unit =
    (access, dataFlow) match {
      case ("Read", false)  => sb.append(s"  $from -.->|Read| $to\n")
      case ("Read", true)   => sb.append(s"  $to -.->|Read| $from\n")
      case ("Write", _)     => sb.append(s"  $from ==>|Write| $to\n")
      case ("ReadWrite", _) => sb.append(s"  $from -->|ReadWrite| $to\n")
      case _                => sb.append(s"  $from -->|$access| $to\n")
    }

  private def integrationStyle(rtype: String): String = rtype match {
    case "grpc"  => "grpcNode"
    case "kafka" => "kafkaNode"
    case "s3"    => "s3Node"
    case _       => "dbNode"
  }

  private def renderNestedSubgraphs[A](
      sb: StringBuilder,
      entries: Seq[(List[(String, String)], A)],
      classNames: Set[String],
      indent: String,
  )(renderLeaf: (StringBuilder, A, String) => Unit): Unit = {
    val (leaves, nested) = entries.partition(_._1.isEmpty)
    for ((_, entry) <- leaves) renderLeaf(sb, entry, indent)
    val byFirst = nested.groupBy(_._1.head)
    for (((level, value), group) <- byFirst) {
      val sgId  = s"ext_group_${sanitizeId(s"${level}_$value")}"
      val label = if (classNames.contains(value)) s"$value (ext)" else value
      sb.append(s"""${indent}subgraph $sgId ["$label"]\n""")
      val remaining = group.map { case (cs, e) => (cs.tail, e) }
      renderNestedSubgraphs(sb, remaining, classNames, indent + "  ")(renderLeaf)
      sb.append(s"${indent}end\n")
    }
  }
}
