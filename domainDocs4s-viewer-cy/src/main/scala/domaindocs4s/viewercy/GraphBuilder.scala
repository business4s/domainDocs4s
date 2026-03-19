package domaindocs4s.viewercy

import scala.scalajs.js

/** Converts LineageData into Cytoscape.js elements (nodes + edges). */
object GraphBuilder {

  case class GraphData(
      elements: js.Array[js.Object],
      groupIds: Set[String],
      /** Maps folded node ID → list of child resource keys that were merged into it. */
      foldedContents: Map[String, List[Resource]],
  )

  val accessColors = Map(
    "Read"      -> "#d4edda",
    "Write"     -> "#f8d7da",
    "ReadWrite" -> "#fff3cd",
    "Pure"      -> "#ffffff",
  )

  val accessBorders = Map(
    "Read"      -> "#28a745",
    "Write"     -> "#dc3545",
    "ReadWrite" -> "#ffc107",
    "Pure"      -> "#cccccc",
  )

  private val resourceColors = Map(
    "database" -> ("#d1ecf1", "#17a2b8"),
    "grpc"     -> ("#e8daef", "#8e44ad"),
    "kafka"    -> ("#d5f5e3", "#27ae60"),
    "s3"       -> ("#ffe0b2", "#fb8c00"),
  )

  /** Compute effective segments with virtual container prepended if configured. */
  private def effectiveSegments(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig]): List[Segment] =
    config.get(resourceType).flatMap(_.containerLabel) match {
      case Some(label) => Segment("container", label) :: segments
      case None        => segments
    }

  /** Compute fold index within effective segments. */
  private def foldIndex(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig]): Option[Int] =
    config.get(resourceType).flatMap(_.foldAtLevel).flatMap { level =>
      val segs = effectiveSegments(segments, resourceType, config)
      val idx  = segs.indexWhere(_.level == level)
      if (idx >= 0) Some(idx) else None
    }

  /** Compute the folded node key for a resource. */
  def foldedNodeKey(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig]): String = {
    val effSegs = effectiveSegments(segments, resourceType, config)
    foldIndex(segments, resourceType, config) match {
      case Some(idx) =>
        val folded = effSegs.take(idx + 1)
        s"$resourceType:${folded.map(s => s"${s.level}=${s.value}").mkString("/")}"
      case None      =>
        s"$resourceType:${effSegs.map(s => s"${s.level}=${s.value}").mkString("/")}"
    }
  }

  /** Compute the display label for a folded/leaf resource node (segments AFTER the group segment). */
  private def childNodeLabel(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig], segLabels: Map[String, String]): String = {
    val effSegs = effectiveSegments(segments, resourceType, config)
    // Drop the first segment (it's the group label), show the rest
    val childSegs = if (effSegs.size > 1) effSegs.tail else effSegs
    val displaySegs = foldIndex(segments, resourceType, config) match {
      case Some(idx) =>
        // idx is in effective segments; we want segments after group (index 0) up to fold
        val folded = effSegs.take(idx + 1)
        if (folded.size > 1) folded.tail else folded
      case None => childSegs
    }
    displaySegs.map(s => displayLabel(s.value, segLabels)).mkString(" / ")
  }

  /** Resolve a segment value to its display label. */
  private def displayLabel(value: String, segmentLabels: Map[String, String]): String =
    segmentLabels.getOrElse(value, value)

  /** Build graph with only the classes visible in the given view. */
  def buildClassLevel(data: LineageData, visibleClassIds: Set[String]): GraphData = {
    val elements = js.Array[js.Object]()
    val groupIds = scala.collection.mutable.Set[String]()
    val config    = data.resourceDisplayConfig
    val segLabels = data.segmentLabels

    val visibleClasses = data.classes.filter(c => visibleClassIds.contains(c.classId))

    // Class group (compound) nodes — only for explicit groups
    val classGroups = visibleClasses.flatMap(_.group).distinct
    for (group <- classGroups) {
      val gid = s"group_$group"
      groupIds += gid
      elements.push(js.Dynamic.literal(
        group = "nodes",
        data = js.Dynamic.literal(
          id = gid,
          label = group,
          nodeType = "classGroup",
        ),
        classes = "compound classGroup",
      ).asInstanceOf[js.Object])
    }

    // Class nodes (children of groups via `parent`)
    for (cls <- visibleClasses) {
      val bg     = accessColors.getOrElse(cls.effectiveAccess, "#ffffff")
      val border = accessBorders.getOrElse(cls.effectiveAccess, "#cccccc")
      val parentId = cls.group.map(g => s"group_$g").orNull
      elements.push(js.Dynamic.literal(
        group = "nodes",
        data = js.Dynamic.literal(
          id = cls.classId,
          label = cls.displayName,
          parent = parentId,
          nodeType = "class",
          packageName = cls.packageName,
          bg = bg,
          borderColor = border,
          accessType = cls.effectiveAccess,
        ),
        classes = s"classNode ${cls.effectiveAccess}",
      ).asInstanceOf[js.Object])
    }

    // ── Resource rendering ──
    // One level of compound: first effective segment becomes a group node.
    // Folded/leaf resources are flat children of that group.

    val createdGroups = scala.collection.mutable.Set[String]()
    val createdNodes  = scala.collection.mutable.Set[String]()
    val foldedContents = scala.collection.mutable.Map[String, List[Resource]]()

    /** Ensure the resource group (first effective segment) exists. Returns its ID. */
    def ensureResourceGroup(resourceType: String, segments: List[Segment]): Option[String] = {
      val effSegs = effectiveSegments(segments, resourceType, config)
      if (effSegs.isEmpty) return None
      val firstSeg = effSegs.head
      val gid = s"rgroup_${resourceType}_${firstSeg.level}_${firstSeg.value}"
      if (!createdGroups.contains(gid)) {
        createdGroups += gid
        groupIds += gid
        val (bg, border) = resourceColors.getOrElse(resourceType, ("#f0f0f0", "#999999"))
        elements.push(js.Dynamic.literal(
          group = "nodes",
          data = js.Dynamic.literal(
            id = gid,
            label = displayLabel(firstSeg.value, segLabels),
            nodeType = "resourceGroup",
            resourceType = resourceType,
            bg = bg,
            borderColor = border,
          ),
          classes = "compound resourceGroup",
        ).asInstanceOf[js.Object])
      }
      Some(gid)
    }

    // Group resources by folded key, create one node per group
    val resourcesByFoldedKey: Map[String, List[Resource]] = data.resources
      .groupBy(r => foldedNodeKey(r.segments, r.resourceType, config))

    for ((foldKey, resources) <- resourcesByFoldedKey) {
      val nid = sanitizeNodeId(foldKey)
      if (!createdNodes.contains(nid)) {
        createdNodes += nid
        val rep = resources.head
        val parentId = ensureResourceGroup(rep.resourceType, rep.segments)
        val label = childNodeLabel(rep.segments, rep.resourceType, config, segLabels)
        val (bg, border) = resourceColors.getOrElse(rep.resourceType, ("#f0f0f0", "#999999"))
        val isFolded = resources.size > 1 || foldIndex(rep.segments, rep.resourceType, config).isDefined
        elements.push(js.Dynamic.literal(
          group = "nodes",
          data = js.Dynamic.literal(
            id = nid,
            label = label,
            parent = parentId.orNull,
            nodeType = "resource",
            resourceType = rep.resourceType,
            resourceKey = foldKey,
            bg = bg,
            borderColor = border,
            folded = isFolded,
            foldedCount = resources.size,
          ),
          classes = s"resourceNode ${rep.resourceType}" + (if (isFolded) " foldedResource" else ""),
        ).asInstanceOf[js.Object])
        if (isFolded) {
          foldedContents(nid) = resources
        }
      }
    }

    // Class-to-class call edges (deduplicated)
    val classEdges = data.callGraph
      .map(e => (s"cls_${e.caller.packageName.hashCode.abs}_${e.caller.className}",
                  s"cls_${e.callee.packageName.hashCode.abs}_${e.callee.className}"))
      .distinct
      .filter { case (from, to) => from != to && visibleClassIds.contains(from) && visibleClassIds.contains(to) }
    for ((from, to) <- classEdges) {
      elements.push(js.Dynamic.literal(
        group = "edges",
        data = js.Dynamic.literal(
          id = s"call_${from}_$to",
          source = from,
          target = to,
          edgeType = "call",
        ),
        classes = "callEdge",
      ).asInstanceOf[js.Object])
    }

    // Integration edges (class -> folded resource node)
    val allNodeIds = createdNodes.toSet

    val integrationEdges = data.integrations
      .map { i =>
        val classId = s"cls_${i.method.packageName.hashCode.abs}_${i.method.className}"
        val foldKey = foldedNodeKey(i.segments, i.resourceType, config)
        val resId   = sanitizeNodeId(foldKey)
        (classId, resId, i.accessType)
      }
      .filter { case (from, to, _) => visibleClassIds.contains(from) && allNodeIds.contains(to) }
      .groupBy { case (from, to, _) => (from, to) }
      .map { case ((from, to), entries) =>
        val combined = combineAccess(entries.map(_._3).toList)
        (from, to, combined)
      }

    for ((from, to, access) <- integrationEdges) {
      val label = access match {
        case "Read"      => "R"
        case "Write"     => "W"
        case "ReadWrite" => "RW"
        case _           => ""
      }
      elements.push(js.Dynamic.literal(
        group = "edges",
        data = js.Dynamic.literal(
          id = s"int_${from}_$to",
          source = from,
          target = to,
          label = label,
          edgeType = "integration",
          accessType = access,
        ),
        classes = s"integrationEdge $access",
      ).asInstanceOf[js.Object])
    }

    // Resource dependency edges
    val resourceKeyToNodeId: Map[String, String] = data.resources.map { r =>
      val foldKey = foldedNodeKey(r.segments, r.resourceType, config)
      r.key -> sanitizeNodeId(foldKey)
    }.toMap

    for (dep <- data.resourceDependencies) {
      for {
        fromId <- resourceKeyToNodeId.get(dep.from)
        toId   <- resourceKeyToNodeId.get(dep.to)
        if fromId != toId
      } {
        elements.push(js.Dynamic.literal(
          group = "edges",
          data = js.Dynamic.literal(
            id = s"rdep_${dep.from}_${dep.to}",
            source = fromId,
            target = toId,
            edgeType = "resourceDep",
          ),
          classes = "resourceDepEdge",
        ).asInstanceOf[js.Object])
      }
    }

    // Compute edge endpoint offsets for parallel edges sharing the same source or target
    assignEdgeOffsets(elements)

    GraphData(elements, groupIds.toSet, foldedContents.toMap)
  }

  /** Build child elements for expanding a folded resource node. */
  def buildFoldedChildren(
      foldedNodeId: String,
      resources: List[Resource],
      config: Map[String, ResourceTypeDisplayConfig],
      segLabels: Map[String, String],
  ): js.Array[js.Object] = {
    val elements = js.Array[js.Object]()
    for (res <- resources) {
      val effSegs = effectiveSegments(res.segments, res.resourceType, config)
      val label   = effSegs.lastOption.map(s => displayLabel(s.value, segLabels)).getOrElse(res.target)
      val nid     = sanitizeNodeId(res.key)
      val (bg, border) = resourceColors.getOrElse(res.resourceType, ("#f0f0f0", "#999999"))
      elements.push(js.Dynamic.literal(
        group = "nodes",
        data = js.Dynamic.literal(
          id = nid,
          label = label,
          parent = foldedNodeId,
          nodeType = "resource",
          resourceType = res.resourceType,
          resourceKey = res.key,
          bg = bg,
          borderColor = border,
          expandedFrom = foldedNodeId,
        ),
        classes = s"resourceNode ${res.resourceType} expandedChild",
      ).asInstanceOf[js.Object])
    }
    elements
  }

  /** Assign sourceYOffset / targetYOffset to edges so parallel edges don't overlap.
    * Groups edges by source (and by target) and spreads them with a pixel offset.
    */
  private def assignEdgeOffsets(elements: js.Array[js.Object]): Unit = {
    val spacing = 8 // pixels between parallel edges
    val edges = (0 until elements.length).flatMap { i =>
      val el = elements(i).asInstanceOf[js.Dynamic]
      if (el.group.asInstanceOf[String] == "edges") Some((i, el)) else None
    }

    def assignOffsets(groupBy: js.Dynamic => String, fieldName: String): Unit = {
      val groups = edges.groupBy { case (_, el) => groupBy(el) }
      for ((_, group) <- groups if group.size > 1) {
        val n = group.size
        group.zipWithIndex.foreach { case ((_, el), idx) =>
          val offset = (idx - (n - 1) / 2.0) * spacing
          el.data.updateDynamic(fieldName)(offset)
        }
      }
    }

    assignOffsets(el => el.data.source.asInstanceOf[String], "sourceYOffset")
    assignOffsets(el => el.data.target.asInstanceOf[String], "targetYOffset")

    // Ensure all edges have default 0 offsets
    for ((_, el) <- edges) {
      if (js.isUndefined(el.data.sourceYOffset)) el.data.updateDynamic("sourceYOffset")(0)
      if (js.isUndefined(el.data.targetYOffset)) el.data.updateDynamic("targetYOffset")(0)
    }
  }

  def sanitizeNodeId(key: String): String =
    "res_" + key.replaceAll("[^a-zA-Z0-9_]", "_")

  def combineAccess(types: List[String]): String = {
    val set = types.toSet - "Pure"
    if (set.contains("Read") && set.contains("Write")) "ReadWrite"
    else if (set.contains("ReadWrite")) "ReadWrite"
    else if (set.contains("Read")) "Read"
    else if (set.contains("Write")) "Write"
    else "Pure"
  }
}
