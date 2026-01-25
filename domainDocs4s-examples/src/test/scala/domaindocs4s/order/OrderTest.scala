package domaindocs4s.order

import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import domaindocs4s.output.glossary.Glossary
import org.scalatest.freespec.AnyFreeSpec
import domaindocs4s.utils.SnapshotTest

class OrderTest extends AnyFreeSpec {

  given DomainDocsContext = TastyContext.fromCurrentProcess()
  private val collector   = new TastyQueryCollector

  private val docs = collector
    .collectSymbols("domaindocs4s.order")

  "glossary" - {

    "markdown" in {
      val mdGlossary = Glossary.build(docs).asMarkdown
      SnapshotTest.testSnapshot(mdGlossary, "order/glossary.md")
    }

    "html" in {
      val htmlGlossary = Glossary.build(docs).asHtml
      SnapshotTest.testSnapshot(htmlGlossary, "order/glossary.html")
    }

  }

}
