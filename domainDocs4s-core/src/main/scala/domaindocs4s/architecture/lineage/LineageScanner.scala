package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

class LineageScanner(
    packages: List[String],
    scanners: List[IntegrationScanner],
    enrichment: IntegrationGroupConfig = IntegrationGroupConfig(),
    resourceScanners: List[ResourceScanner] = Nil,
)(using ctx: Context) {

  def scan(): ScanResult = {
    val callGraph            = packages.flatMap(new TastyCallGraphExtractor().extract)
    val codeIntegrations     = scanners.flatMap(_.scan(packages))
    val resourceIntegrations = resourceScanners.flatMap(_.scan())
    val integrations         = enrichment.enrich(codeIntegrations ++ resourceIntegrations)
    LineageBuilder.build(callGraph, integrations)
  }
}
