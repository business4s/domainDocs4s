package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

class LineageScanner(
    packages: List[String],
    scanners: List[IntegrationScanner],
    enrichment: IntegrationGroupConfig = IntegrationGroupConfig(),
)(using ctx: Context) {

  def scan(): ScanResult = {
    val callGraph    = packages.flatMap(new TastyCallGraphExtractor().extract)
    val integrations = enrichment.enrich(scanners.flatMap(_.scan(packages)))
    LineageBuilder.build(callGraph, integrations)
  }
}
