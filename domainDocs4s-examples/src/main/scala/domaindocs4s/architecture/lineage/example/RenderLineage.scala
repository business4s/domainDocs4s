package domaindocs4s.architecture.lineage.example

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.example.UserRepo
import domaindocs4s.collector.TastyContext
import tastyquery.Contexts.Context

/** Run this to render the lineage diagram.
  *
  * Execute via: sbt "examples / runMain domaindocs4s.architecture.lineage.example.RenderLineage"
  */
object RenderLineage {

  def main(args: Array[String]): Unit = {
    given ctx: Context = TastyContext.fromCurrentProcess()

    val pkg = "domaindocs4s.architecture.lineage.example"

    val callGraph         = new TastyCallGraphExtractor().extract(pkg)
    val doobieIntegrations = new TastyDoobieScanner().scan(pkg)
    val grpcIntegrations   = new TastyFs2GrpcScanner().scan(pkg)

    val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
    val pekkoIntegrations  = new TastyPekkoJournalScanner().scan(pekkoPkg)

    val manualIntegrations = ManualScanner.builder
      .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
      .build

    val enrichment = IntegrationGroupConfig.builder
      .group[UserRepo]("user-db")
      .build
    val allIntegrations = enrichment.enrich(
      doobieIntegrations ++ grpcIntegrations ++ manualIntegrations ++ pekkoIntegrations,
    )
    val result = LineageBuilder.build(callGraph, allIntegrations)

    println("=== Access direction ===")
    println(MermaidRenderer.toViewUrl(MermaidRenderer.render(result)))

    println("=== Data flow ===")
    println(MermaidRenderer.toViewUrl(MermaidRenderer.renderDataFlow(result)))

    val classLevelConfig = ClassLevelConfig.builder
      .hide[UserRepo]
      .groupByPackage(pkg)
      .build

    println("=== Class-level access direction ===")
    println(MermaidRenderer.toViewUrl(MermaidRenderer.renderClassLevel(result, classLevelConfig)))

    println("=== Class-level data flow ===")
    println(MermaidRenderer.toViewUrl(MermaidRenderer.renderClassLevelDataFlow(result, classLevelConfig)))
  }
}
