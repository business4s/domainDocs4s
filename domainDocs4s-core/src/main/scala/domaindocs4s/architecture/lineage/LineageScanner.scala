package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

class LineageScanner(
    packages: List[String],
    scanners: List[IntegrationScanner],
    adjustments: LineageAdjustments = LineageAdjustments.empty,
    enrichment: IntegrationGroupConfig = IntegrationGroupConfig(),
    resourceScanners: List[ResourceScanner] = Nil,
)(using ctx: Context) {

  def scan(): ScanResult = {
    val rawCallGraph         = packages.flatMap(new TastyCallGraphExtractor().extract)
    val codeIntegrations     = scanners.flatMap(_.scan(packages))
    val resourceIntegrations = resourceScanners.flatMap(_.scan())
    val (callGraph, refined) = adjustments.apply(rawCallGraph, codeIntegrations)
    val (_, refinedResources) = adjustments.apply(Nil, resourceIntegrations)
    val integrations         = enrichment.enrich(refined)
    LineageBuilder
      .build(callGraph, integrations)
      .copy(
        classDisplayNames = adjustments.classRenames,
        classGroups = adjustments.classGroups(callGraph),
        resourceOnlyIntegrations = refinedResources,
      )
  }
}
