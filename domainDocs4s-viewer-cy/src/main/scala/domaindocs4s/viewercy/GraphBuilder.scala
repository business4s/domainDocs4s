package domaindocs4s.viewercy

import scala.scalajs.js

/** Converts LineageData into Cytoscape.js elements (nodes + edges). */
object GraphBuilder {

  case class GraphData(
      elements: js.Array[js.Object],
      groupIds: Set[String],
      /** Maps folded node ID → list of child resource keys that were merged into it. */
      foldedContents: Map[String, List[Resource]],
      /** For system view: elements to add when a resource group is expanded (resource nodes + service→resource edges). */
      expandableElements: Map[String, js.Array[js.Object]] = Map.empty,
      /** For system view: resource node IDs within each group that are accessed by 2+ services. */
      sharedResourceIds: Map[String, Set[String]] = Map.empty,
      /** For system view: aggregate edges per group (for restoring on collapse). */
      aggregateEdges: Map[String, js.Array[js.Object]] = Map.empty,
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

  /** Short label for access type on edges. */
  def accessLabel(access: String): String = access match {
    case "Read" => "R"; case "Write" => "W"; case "ReadWrite" => "RW"; case _ => ""
  }

  /** Compute effective segments with virtual container prepended if configured. */
  def effectiveSegments(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig]): List[Segment] =
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

  /** Compute the display label for a leaf resource node (only the leaf segment, since parents are nested compounds). */
  private def childNodeLabel(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig], segLabels: Map[String, String]): String = {
    val effSegs = effectiveSegments(segments, resourceType, config)
    val fi = foldIndex(segments, resourceType, config)
    val leafSeg = fi match {
      case Some(idx) => if (idx < effSegs.size) Some(effSegs(idx)) else effSegs.lastOption
      case None      => effSegs.lastOption
    }
    leafSeg.map(s => displayLabel(s.value, segLabels)).getOrElse("")
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

    /** Ensure nested resource group compound nodes exist for all container segments.
      * Creates a hierarchy of compound nodes (e.g. database > schema).
      * Returns the innermost compound's ID to use as parent for the leaf node. */
    def ensureResourceGroups(resourceType: String, segments: List[Segment]): Option[String] = {
      val effSegs = effectiveSegments(segments, resourceType, config)
      if (effSegs.isEmpty) return None

      // Container segments: everything except the leaf.
      // For folded resources, leaf is at the fold index; for others, it's the last segment.
      val fi = foldIndex(segments, resourceType, config)
      val containerSegs = fi match {
        case Some(idx) => effSegs.take(idx)
        case None      => effSegs.dropRight(1)
      }
      if (containerSegs.isEmpty) return None

      var parentId: Option[String] = None
      val pathParts = scala.collection.mutable.ListBuffer[String]()
      val accumSegs = scala.collection.mutable.ListBuffer[Segment]()
      for (seg <- containerSegs) {
        pathParts += s"${seg.level}=${seg.value}"
        accumSegs += seg
        val gid = s"rgroup_${resourceType}_${pathParts.mkString("/")}".replaceAll("[^a-zA-Z0-9_]", "_")
        if (!createdGroups.contains(gid)) {
          createdGroups += gid
          groupIds += gid
          val (bg, border) = resourceColors.getOrElse(resourceType, ("#f0f0f0", "#999999"))
          val segArr = js.Array(accumSegs.map(s => js.Dynamic.literal(level = s.level, value = s.value).asInstanceOf[js.Object]).toSeq*)
          elements.push(js.Dynamic.literal(
            group = "nodes",
            data = js.Dynamic.literal(
              id = gid,
              label = displayLabel(seg.value, segLabels),
              parent = parentId.orNull,
              nodeType = "resourceGroup",
              resourceType = resourceType,
              segmentLevel = seg.level,
              segmentValue = seg.value,
              segments = segArr,
              bg = bg,
              borderColor = border,
            ),
            classes = "compound resourceGroup",
          ).asInstanceOf[js.Object])
        }
        parentId = Some(gid)
      }
      parentId
    }

    // Group resources by folded key, create one node per group
    val resourcesByFoldedKey: Map[String, List[Resource]] = data.resources
      .groupBy(r => foldedNodeKey(r.segments, r.resourceType, config))

    for ((foldKey, resources) <- resourcesByFoldedKey) {
      val nid = sanitizeNodeId(foldKey)
      if (!createdNodes.contains(nid)) {
        createdNodes += nid
        val rep = resources.head
        val parentId = ensureResourceGroups(rep.resourceType, rep.segments)
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
      elements.push(js.Dynamic.literal(
        group = "edges",
        data = js.Dynamic.literal(
          id = s"int_${from}_$to",
          source = from,
          target = to,
          label = accessLabel(access),
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

  /** Build system-level graph from multiple services.
    *
    * Initial view: service nodes + flat resource group nodes + aggregated service→group edges.
    * Resource groups start collapsed (not compound). Expanding a group adds its children
    * from `expandableElements` and converts it to a compound node.
    */
  def buildSystemLevel(services: List[ServiceEntry]): GraphData = {
    val elements = js.Array[js.Object]()
    val groupIds = scala.collection.mutable.Set[String]()

    // Merge display config and segment labels across all services
    val config    = services.flatMap(_.data.resourceDisplayConfig.toList).toMap
    val segLabels = services.flatMap(_.data.segmentLabels.toList).toMap

    // Service nodes
    for (service <- services) {
      elements.push(js.Dynamic.literal(
        group = "nodes",
        data = js.Dynamic.literal(
          id = serviceNodeId(service.name),
          label = service.name,
          nodeType = "service",
          bg = "#e3f2fd",
          borderColor = "#1976d2",
        ),
        classes = "serviceNode",
      ).asInstanceOf[js.Object])
    }

    // Merge resources across all services (deduplicate by key)
    val allResources = services.flatMap(_.data.resources).groupBy(_.key).map(_._2.head).toList
    val foldedContents = scala.collection.mutable.Map[String, List[Resource]]()

    // Determine top-level resource groups: first effective segment per resource type
    // Each unique (resourceType, firstSegLevel, firstSegValue) becomes one group node.
    case class GroupInfo(resourceType: String, segLevel: String, segValue: String, label: String)

    val resourceToGroup = scala.collection.mutable.Map[String, String]() // foldedKey -> groupId
    val groupInfos = scala.collection.mutable.LinkedHashMap[String, GroupInfo]()

    val resourcesByFoldedKey = allResources.groupBy(r => foldedNodeKey(r.segments, r.resourceType, config))

    for ((foldKey, resources) <- resourcesByFoldedKey) {
      val rep = resources.head
      val effSegs = effectiveSegments(rep.segments, rep.resourceType, config)
      if (effSegs.nonEmpty) {
        val firstSeg = effSegs.head
        val gid = s"rgroup_${rep.resourceType}_${firstSeg.level}_${firstSeg.value}".replaceAll("[^a-zA-Z0-9_]", "_")
        resourceToGroup(foldKey) = gid
        if (!groupInfos.contains(gid)) {
          groupInfos(gid) = GroupInfo(rep.resourceType, firstSeg.level, firstSeg.value, displayLabel(firstSeg.value, segLabels))
        }
      }
      if (resources.size > 1 || foldIndex(rep.segments, rep.resourceType, config).isDefined) {
        foldedContents(sanitizeNodeId(foldKey)) = resources
      }
    }

    // Create flat resource group nodes (not compound — children added on expand)
    for ((gid, info) <- groupInfos) {
      groupIds += gid
      val (bg, border) = resourceColors.getOrElse(info.resourceType, ("#f0f0f0", "#999999"))
      val segArr = js.Array(js.Dynamic.literal(level = info.segLevel, value = info.segValue).asInstanceOf[js.Object])
      elements.push(js.Dynamic.literal(
        group = "nodes",
        data = js.Dynamic.literal(
          id = gid,
          label = info.label,
          nodeType = "resourceGroup",
          resourceType = info.resourceType,
          segmentLevel = info.segLevel,
          segmentValue = info.segValue,
          segments = segArr,
          bg = bg,
          borderColor = border,
        ),
        classes = s"resourceGroup collapsedGroup ${info.resourceType}",
      ).asInstanceOf[js.Object])
    }

    // Service → resource group edges (aggregated)
    // Includes both integrations and resource discoveries (for orphaned integrations)
    val aggEdgesByGroup = scala.collection.mutable.Map[String, js.Array[js.Object]]()
    for (service <- services) {
      val svcId = serviceNodeId(service.name)
      val accessPerGroup = scala.collection.mutable.Map[String, List[String]]()
      for (i <- service.data.integrations) {
        val foldKey = foldedNodeKey(i.segments, i.resourceType, config)
        resourceToGroup.get(foldKey).foreach { gid =>
          accessPerGroup(gid) = i.accessType :: accessPerGroup.getOrElse(gid, Nil)
        }
      }
      for (res <- service.data.resources if res.discoveries.nonEmpty) {
        val foldKey = foldedNodeKey(res.segments, res.resourceType, config)
        resourceToGroup.get(foldKey).foreach { gid =>
          accessPerGroup(gid) = res.discoveries.map(_.accessType) ++ accessPerGroup.getOrElse(gid, Nil)
        }
      }
      val edgesByGroup = accessPerGroup.map { case (gid, accesses) =>
        (gid, combineAccess(accesses))
      }

      for ((gid, access) <- edgesByGroup) {
        val edge = js.Dynamic.literal(
          group = "edges",
          data = js.Dynamic.literal(
            id = s"svc_${svcId}_$gid",
            source = svcId,
            target = gid,
            label = accessLabel(access),
            edgeType = "serviceIntegration",
            accessType = access,
            serviceName = service.name,
          ),
          classes = s"integrationEdge $access aggregatedEdge",
        ).asInstanceOf[js.Object]
        elements.push(edge)
        aggEdgesByGroup.getOrElseUpdate(gid, js.Array[js.Object]()).push(edge)
      }
    }

    assignEdgeOffsets(elements)

    // Build expandable elements per group: resource nodes + service→resource edges
    val expandable = scala.collection.mutable.Map[String, js.Array[js.Object]]()
    val createdNodes = scala.collection.mutable.Set[String]()

    for ((foldKey, resources) <- resourcesByFoldedKey) {
      resourceToGroup.get(foldKey).foreach { gid =>
        val groupElements = expandable.getOrElseUpdate(gid, js.Array[js.Object]())
        val nid = sanitizeNodeId(foldKey)
        if (!createdNodes.contains(nid)) {
          createdNodes += nid
          val rep = resources.head
          val label = childNodeLabel(rep.segments, rep.resourceType, config, segLabels)
          val (bg, border) = resourceColors.getOrElse(rep.resourceType, ("#f0f0f0", "#999999"))
          val isFolded = resources.size > 1 || foldIndex(rep.segments, rep.resourceType, config).isDefined
          groupElements.push(js.Dynamic.literal(
            group = "nodes",
            data = js.Dynamic.literal(
              id = nid,
              label = label,
              parent = gid,
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
        }
      }
    }

    // Service → individual resource edges (for expanded view)
    // Uses both integrations (class-level scanner output) and resource discoveries
    // (for resources whose integrations were orphaned by .show adjustments).
    val edgeTracker = scala.collection.mutable.Set[(String, String)]() // (svcId, resId) → already has edge
    for (service <- services) {
      val svcId = serviceNodeId(service.name)
      val edgesByTarget = service.data.integrations
        .flatMap { i =>
          val foldKey = foldedNodeKey(i.segments, i.resourceType, config)
          val resId = sanitizeNodeId(foldKey)
          resourceToGroup.get(foldKey).map(gid => (gid, resId, i.accessType))
        }
        .groupBy { case (_, resId, _) => resId }
        .map { case (resId, entries) =>
          val gid = entries.head._1
          val combined = combineAccess(entries.map(_._3).toList)
          (gid, resId, combined, service.name)
        }

      for ((gid, resId, access, svcName) <- edgesByTarget) {
        edgeTracker += ((svcId, resId))
        val groupElements = expandable.getOrElseUpdate(gid, js.Array[js.Object]())
        groupElements.push(js.Dynamic.literal(
          group = "edges",
          data = js.Dynamic.literal(
            id = s"exp_${svcId}_$resId",
            source = svcId,
            target = resId,
            label = accessLabel(access),
            edgeType = "serviceIntegration",
            accessType = access,
            serviceName = svcName,
          ),
          classes = s"integrationEdge $access",
        ).asInstanceOf[js.Object])
      }

      // Fill gaps: resources with discoveries but no integration edge (orphaned by .show)
      for (res <- service.data.resources if res.discoveries.nonEmpty) {
        val foldKey = foldedNodeKey(res.segments, res.resourceType, config)
        val resId = sanitizeNodeId(foldKey)
        if (!edgeTracker.contains((svcId, resId))) {
          resourceToGroup.get(foldKey).foreach { gid =>
            edgeTracker += ((svcId, resId))
            val access = combineAccess(res.discoveries.map(_.accessType))
            val groupElements = expandable.getOrElseUpdate(gid, js.Array[js.Object]())
            groupElements.push(js.Dynamic.literal(
              group = "edges",
              data = js.Dynamic.literal(
                id = s"exp_${svcId}_$resId",
                source = svcId,
                target = resId,
                label = accessLabel(access),
                edgeType = "serviceIntegration",
                accessType = access,
                serviceName = service.name,
              ),
              classes = s"integrationEdge $access",
            ).asInstanceOf[js.Object])
          }
        }
      }
    }

    // Compute which resources are shared (accessed by 2+ services), per group
    // Uses the same edgeTracker that already includes both integrations and discovery-based edges
    val resourceServiceCount = scala.collection.mutable.Map[String, scala.collection.mutable.Set[String]]()
    for ((svcId, resId) <- edgeTracker) {
      resourceServiceCount.getOrElseUpdate(resId, scala.collection.mutable.Set[String]()) += svcId
    }
    val sharedIds = resourceServiceCount.collect { case (resId, svcs) if svcs.size >= 2 => resId }.toSet
    val sharedByGroup = groupIds.map { gid =>
      val groupResIds = expandable.getOrElse(gid, js.Array()).toList.flatMap { el =>
        val d = el.asInstanceOf[js.Dynamic]
        if (d.group.asInstanceOf[String] == "nodes") {
          Some(d.data.id.asInstanceOf[String])
        } else None
      }.toSet
      gid -> (groupResIds & sharedIds)
    }.toMap

    GraphData(elements, groupIds.toSet, foldedContents.toMap, expandable.toMap, sharedByGroup, aggEdgesByGroup.toMap)
  }

  def serviceNodeId(name: String): String =
    s"svc_${name.replaceAll("[^a-zA-Z0-9_]", "_")}"

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
