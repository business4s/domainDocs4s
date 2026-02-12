package domaindocs4s.banking

object Main extends App {

  // start_collector
  import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}

  // Setup collector
  given DomainDocsContext = TastyContext.fromCurrentProcess()
  val collector           = new TastyQueryCollector

  // Collect documentation from your domain package
  val docs = collector
    .collectSymbols("domaindocs4s.banking.application")
  // end_collector

  // start_glossary
  import domaindocs4s.output.glossary.Glossary

  // Build glossary
  val glossary = Glossary.build(docs)
  // end_glossary

  // start_md_glossary
  import domaindocs4s.output.Writer

  val glossaryMd = glossary.asMarkdown
  Writer(glossaryMd, "glossary.md")
  // end_md_glossary

  // start_html_glossary
  import domaindocs4s.output.Writer

  val glossaryHtml = glossary.asHtml
  Writer(glossaryHtml, "glossary.html")
  // end_html_glossary

  // start_diagram
  // Build diagram and render it as markdown
  import domaindocs4s.output.diagram.Diagram
  import domaindocs4s.output.diagram.Direction
  import domaindocs4s.output.Writer

  val diagram =
    Diagram
      .build(docs)
      .asMarkdown(Direction.TB)

  // Write diagram to file
  Writer(diagram, "diagram.md")
  // end_diagram
}
