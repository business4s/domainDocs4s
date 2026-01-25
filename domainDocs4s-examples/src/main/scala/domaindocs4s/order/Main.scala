package domaindocs4s.order

// start_generate
import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import domaindocs4s.output.Writer
import domaindocs4s.output.diagram.Diagram
import domaindocs4s.output.glossary.Glossary

@main def generateDomainDocs(): Unit = {

  // 1) Setup collector
  given DomainDocsContext = TastyContext.fromCurrentProcess()
  val collector           = new TastyQueryCollector

  // 2) Collect documentation model
  val docs =
    collector
      .collectSymbols("domaindocs4s.order") // the package with your domain

  // 3) Generate glossary markdown (or other supported output and format)
  val glossary =
    Glossary
      .build(docs) // build glossary
      .asMarkdown  // render markdown

  // 4) Write the result to file
  Writer(glossary, "glossary.md")

  val diagram =
    Diagram
      .build(docs)  // build glossary
      .asMarkdown() // render markdown

  // 4) Write the result to file
  Writer(diagram, "diagram.md")
}
// end_generate
