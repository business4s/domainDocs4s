package domaindocs4s.banking

import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import domaindocs4s.output.Writer
import domaindocs4s.output.diagram.{Diagram, Direction}

object Main {

  def main(args: Array[String]): Unit = {

    given DomainDocsContext = TastyContext.fromCurrentProcess()
    val collector           = new TastyQueryCollector

    val docs = collector
      .collectSymbols("domaindocs4s.banking.party")

    // start_generate
    val diagram =
      Diagram
        .build(                  // build diagram from collected documentation
          docs = docs,           // documentation model
          onlyDocumented = false, // includes first undocumented parent (often package name) when false (default)
        )
        .asMarkdown(                // render markdown
          direction = Direction.LR, // diagram direction (since not all renderers support direction, the default is None)
          withFields = true,        // includes class fields in diagram when true (default)
        )
    // end_generate

    Writer(diagram, "diagram.md")
  }
}
