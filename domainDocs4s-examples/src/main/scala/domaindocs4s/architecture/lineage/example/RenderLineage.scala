package domaindocs4s.architecture.lineage.example

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.ResourceId
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

    val result = new LineageScanner(
      packages = List(
        "domaindocs4s.architecture.lineage.example",
        "domaindocs4s.architecture.lineage.example.pekko",
        "domaindocs4s.architecture.lineage.example.slick",
        "domaindocs4s.architecture.lineage.example.fs2kafka",
      ),
      scanners = List(
        new TastyDoobieScanner(),
        new TastyFs2GrpcScanner(),
        new TastyPekkoJournalScanner(resource = ResourceId.DbTable("journal", database = Some("user-db"))),
        new TastySlickScanner(),
        new TastyPekkoKafkaScanner(),
        new TastyFs2KafkaScanner(),
      ),
      adjustments = LineageAdjustments.builder
        .method[EventPublisher](_.publishDeposit)
        .writes
        .kafka("user.deposit-events")
        .cls[UserRepo]
        .remove
        .build,
    ).scan()

    println("=== Access direction ===")
    println(MermaidRenderer.toViewUrl(MermaidRenderer.render(result)))

    println("=== Data flow ===")
    println(MermaidRenderer.toViewUrl(MermaidRenderer.renderDataFlow(result)))

    val classLevelConfig = ClassLevelConfig.builder
      .groupByPackage("domaindocs4s.architecture.lineage.example")
      .build

    println("=== Class-level access direction ===")
    println(MermaidRenderer.toViewUrl(MermaidRenderer.renderClassLevel(result, classLevelConfig)))

    println("=== Class-level data flow ===")
    println(MermaidRenderer.toViewUrl(MermaidRenderer.renderClassLevelDataFlow(result, classLevelConfig)))
  }
}
