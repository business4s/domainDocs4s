package domaindocs4s.custom

// start_generate
import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import domaindocs4s.output.Writer

object CustomMain extends App {

  given DomainDocsContext = TastyContext.fromCurrentProcess()
  val collector           = new TastyQueryCollector

  val docs = collector.collectSymbols("domaindocs4s.custom")

  val custom =
    CustomGenerator.build(docs)

  Writer(custom.asMarkdown(), "custom.md")
}
// end_generate
