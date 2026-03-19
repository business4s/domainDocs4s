package domaindocs4s.viewercy

import scala.scalajs.js

/** Builds a cross-service graph: services as nodes, resource groups as shared nodes, edges connecting them. */
object ConnectedViewBuilder {

  case class ConnectedGraphData(
      elements: js.Array[js.Object],
  )

  /** Build the connected view from multiple services.
    * Each service becomes a node. Resource groups (first effective segment per resource type)
    * become shared nodes. Edges connect services to their resource groups with combined access.
    */
  def build(services: List[ServiceEntry]): ConnectedGraphData = {
    val elements = js.Array[js.Object]()

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

    // Collect resource groups across all services
    // A resource group = (resourceType, first effective segment level+value)
    case class ResGroupKey(resourceType: String, segLevel: String, segValue: String)

    val createdGroups = scala.collection.mutable.Set[String]()
    val serviceToGroups = scala.collection.mutable.Map[String, scala.collection.mutable.Map[String, String]]()

    val resourceColors = Map(
      "database" -> ("#d1ecf1", "#17a2b8"),
      "grpc"     -> ("#e8daef", "#8e44ad"),
      "kafka"    -> ("#d5f5e3", "#27ae60"),
      "s3"       -> ("#ffe0b2", "#fb8c00"),
    )

    for (service <- services) {
      val config = service.data.resourceDisplayConfig
      val segLabels = service.data.segmentLabels

      // Group resources by their resource group (first effective segment)
      val groupsForService = scala.collection.mutable.Map[String, String]() // groupNodeId -> combined access

      for (resource <- service.data.resources) {
        val effSegs = effectiveSegments(resource.segments, resource.resourceType, config)
        if (effSegs.nonEmpty) {
          val firstSeg = effSegs.head
          val gid = resourceGroupNodeId(resource.resourceType, firstSeg.level, firstSeg.value)
          val label = segLabels.getOrElse(firstSeg.value, firstSeg.value)

          // Create group node if not already done
          if (createdGroups.add(gid)) {
            val (bg, border) = resourceColors.getOrElse(resource.resourceType, ("#f0f0f0", "#999999"))
            elements.push(js.Dynamic.literal(
              group = "nodes",
              data = js.Dynamic.literal(
                id = gid,
                label = label,
                nodeType = "resourceGroup",
                resourceType = resource.resourceType,
                bg = bg,
                borderColor = border,
              ),
              classes = s"resourceGroupNode ${resource.resourceType}",
            ).asInstanceOf[js.Object])
          }

          groupsForService(gid) = "Pure" // will be overridden by integration access
        }
      }

      // Determine access types from integrations
      val accessByGroup = scala.collection.mutable.Map[String, List[String]]()
      for (integ <- service.data.integrations) {
        val effSegs = effectiveSegments(integ.segments, integ.resourceType, config)
        if (effSegs.nonEmpty) {
          val firstSeg = effSegs.head
          val gid = resourceGroupNodeId(integ.resourceType, firstSeg.level, firstSeg.value)
          accessByGroup(gid) = integ.accessType :: accessByGroup.getOrElse(gid, Nil)
        }
      }

      // Create edges from service to resource groups
      val allGroupIds = groupsForService.keySet ++ accessByGroup.keySet
      for (gid <- allGroupIds) {
        val accessTypes = accessByGroup.getOrElse(gid, List("Pure"))
        val combined = GraphBuilder.combineAccess(accessTypes)
        val label = combined match {
          case "Read" => "R"; case "Write" => "W"; case "ReadWrite" => "RW"; case _ => ""
        }
        val svcId = serviceNodeId(service.name)
        val edgeId = s"svc_${svcId}_$gid"
        elements.push(js.Dynamic.literal(
          group = "edges",
          data = js.Dynamic.literal(
            id = edgeId,
            source = svcId,
            target = gid,
            label = label,
            edgeType = "serviceIntegration",
            accessType = combined,
          ),
          classes = s"integrationEdge $combined",
        ).asInstanceOf[js.Object])
      }
    }

    ConnectedGraphData(elements)
  }

  def serviceNodeId(name: String): String =
    s"svc_${name.replaceAll("[^a-zA-Z0-9_]", "_")}"

  private def resourceGroupNodeId(resourceType: String, level: String, value: String): String =
    s"rgroup_${resourceType}_${level}_${value}".replaceAll("[^a-zA-Z0-9_]", "_")

  def effectiveSegmentsPublic(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig]): List[Segment] =
    effectiveSegments(segments, resourceType, config)

  private def effectiveSegments(segments: List[Segment], resourceType: String, config: Map[String, ResourceTypeDisplayConfig]): List[Segment] =
    config.get(resourceType).flatMap(_.containerLabel) match {
      case Some(label) => Segment("container", label) :: segments
      case None        => segments
    }
}
