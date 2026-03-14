package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

class LineageScanner(
    packages: List[String],
    scanners: List[IntegrationScanner],
    adjustments: LineageAdjustments = LineageAdjustments.empty,
    enrichment: IntegrationGroupConfig = IntegrationGroupConfig(),
    resourceScanners: List[ResourceScanner] = Nil,
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

    val (callGraph, refined)  = logger.timed("Adjustments (code)") {
      adjustments.apply(rawCallGraph, codeIntegrations)
    }
    val (_, refinedResources) = adjustments.apply(Nil, resourceIntegrations)
    val integrations          = enrichment.enrich(refined)

    val result = logger.timed("Phase 2: Lineage building") {
      LineageBuilder
        .build(callGraph, integrations)
        .copy(
          classDisplayNames = adjustments.classRenames,
          classGroups = adjustments.classGroups(callGraph),
          resourceOnlyIntegrations = refinedResources,
          resourceDependencies = resourceDeps,
        )
    }

    logger.log(
      s"Result: ${result.classes.size} classes, ${result.allMethods.size} methods, " +
        s"${result.integrations.size} integrations, ${result.lineageChains.size} lineage chains, " +
        s"${result.resources.size} resources",
    )

    result
  }
}
