package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

class LineageScanner(
    packages: List[String],
    scanners: List[IntegrationScanner],
    adjustments: LineageAdjustments = LineageAdjustments.empty,
    resourceScanners: List[ResourceScanner] = Nil,
    segmentLabels: Map[String, String] = Map.empty,
    logger: LineageLogger = LineageLogger.fromSystemProperty(),
)(using ctx: Context) {

  def scan(): ScanResult = logger.timed("Lineage scan") {
    val rawCallGraph = logger.timed("Phase 0: Call graph extraction") {
      packages.flatMap(new TastyCallGraphExtractor().extract)
    }
    logger.log(s"  extracted ${rawCallGraph.size} methods, ${rawCallGraph.map(_.calls.size).sum} call edges")

    val codeIntegrations = logger.timed("Phase 1: Code integration scanning") {
      scanners.flatMap { s =>
        logger.timed(s"  scanner ${s.getClass.getSimpleName}") {
          val results = s.scan(packages)
          logger.log(s"    found ${results.size} integrations")
          results
        }
      }
    }

    val resourceIntegrations = logger.timed("Phase 1: Resource scanning") {
      resourceScanners.flatMap { s =>
        logger.timed(s"  scanner ${s.getClass.getSimpleName}") {
          val results = s.scan()
          logger.log(s"    found ${results.size} integrations")
          results
        }
      }
    }

    val resourceDeps = resourceScanners.flatMap(_.scanDependencies())
    logger.log(s"  resource dependencies: ${resourceDeps.size}")

    val (callGraph, refined, orphanedCode) = logger.timed("Adjustments (code)") {
      adjustments.apply(rawCallGraph, codeIntegrations)
    }
    val (_, refinedResources, _) = adjustments.apply(Nil, resourceIntegrations)

    // Upgrade less-specific ResourceIds in code integrations to more-specific ones from resource scanners.
    // e.g., Doobie detects `database:table=X`, Flyway knows `database:database=InternalDB/table=X` → upgrade Doobie's.
    val integrations = upgradeResourceIds(refined, refinedResources)

    val views = adjustments.extractViews(rawCallGraph)

    val result = logger.timed("Phase 2: Lineage building") {
      val base = LineageBuilder.build(callGraph, integrations)

      // Add back classes that were hidden by ShowClass (for the viewer — views control visibility)
      val existingClassKeys = base.classes.map(c => (c.packageName, c.name)).toSet
      val hiddenClasses = rawCallGraph
        .groupBy(m => (m.packageName, m.className))
        .collect { case (key, methods) if !existingClassKeys.contains(key) =>
          val scannedMethods = methods.map(m =>
            ScannedMethod(m.ref, DataAccessType.Pure, DataAccessType.Pure, m.calls, Nil),
          )
          ScannedClass(name = key._2, packageName = key._1, methods = scannedMethods)
        }
        .toList
        .sortBy(_.name)

      base.copy(
        classes = base.classes ++ hiddenClasses,
        classDisplayNames = adjustments.classRenames,
        classGroups = adjustments.classGroups(rawCallGraph),
        resourceOnlyIntegrations = refinedResources ++ orphanedCode,
        resourceDependencies = resourceDeps,
        views = views,
        segmentLabels = segmentLabels,
      )
    }

    logger.log(
      s"Result: ${result.classes.size} classes, ${result.allMethods.size} methods, " +
        s"${result.integrations.size} integrations, ${result.lineageChains.size} lineage chains, " +
        s"${result.resources.size} resources",
    )

    result
  }

  /** Upgrade less-specific ResourceIds in code integrations when a more-specific match exists in resource integrations.
    *
    * When the same (resourceType, label) exists with different specificity — e.g., `database:table=X` from Doobie and
    * `database:database=InternalDB/table=X` from Flyway — the code integration's ResourceId is upgraded to the more specific one. This ensures
    * diagram edges point to the correct merged resource node.
    */
  private def upgradeResourceIds(
      codeIntegrations: List[DiscoveredIntegration],
      resourceIntegrations: List[DiscoveredIntegration],
  ): List[DiscoveredIntegration] = {
    // Build lookup: (resourceType, label) → best ResourceId.
    // Prefer resource scanners (Flyway, etc.) over code scanners (Doobie, etc.) when segment count is equal,
    // since resource scanners have authoritative schema information.
    val resourceKeys = resourceIntegrations.map(i => (i.resourceType, i.target) -> i.resourceId).toMap
    val allIntegrations = codeIntegrations ++ resourceIntegrations
    val byTypeAndLabel  = allIntegrations.groupBy(i => (i.resourceType, i.target))
    val upgrades: Map[(ResourceType, String), ResourceId] = byTypeAndLabel.collect {
      case ((rtype, label), dis) if dis.map(_.resourceId.key).distinct.size > 1 =>
        // Prefer resource scanner's ResourceId, fall back to most-specific by segment count
        val best = resourceKeys.getOrElse(
          (rtype, label),
          dis.map(_.resourceId).maxBy(_.segments.size),
        )
        (rtype, label) -> best
    }

    if (upgrades.isEmpty) codeIntegrations
    else
      codeIntegrations.map { i =>
        upgrades.get((i.resourceType, i.target)) match {
          case Some(better) if better.key != i.resourceId.key => i.copy(resourceId = better)
          case _                                              => i
        }
      }
  }
}
