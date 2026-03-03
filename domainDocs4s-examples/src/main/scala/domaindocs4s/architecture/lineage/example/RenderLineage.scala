package domaindocs4s.architecture.lineage.example

import domaindocs4s.architecture.lineage.*
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

    val callGraph    = new TastyCallGraphExtractor().extract(pkg)
    val integrations = new TastyDoobieScanner().scan(pkg)
    val result       = LineageBuilder.build(callGraph, integrations)

    val mermaid = MermaidRenderer.render(result)
    val url     = MermaidRenderer.toViewUrl(mermaid)

    println("=== Mermaid Diagram ===")
    println(mermaid)
    println("=== View URL ===")
    println(url)
  }
}
