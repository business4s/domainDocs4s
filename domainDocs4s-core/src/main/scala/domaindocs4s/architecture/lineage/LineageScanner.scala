package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

class LineageScanner(
    packages: List[String],
    scanners: List[IntegrationScanner],
    manual: ManualDeclarations = ManualDeclarations.empty,
    enrichment: IntegrationGroupConfig = IntegrationGroupConfig(),
    resourceScanners: List[ResourceScanner] = Nil,
)(using ctx: Context) {

  def scan(): ScanResult = {
    val callGraph            = packages.flatMap(new TastyCallGraphExtractor().extract)
    val codeIntegrations     = scanners.flatMap(_.scan(packages))
    val resourceIntegrations = resourceScanners.flatMap(_.scan())
    val rawIntegrations      = codeIntegrations ++ resourceIntegrations
    val refined              = manual.apply(rawIntegrations)
    val integrations         = enrichment.enrich(refined)
    LineageBuilder.build(callGraph, integrations)
  }
}
