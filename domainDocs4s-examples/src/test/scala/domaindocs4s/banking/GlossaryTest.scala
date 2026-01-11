package domaindocs4s.banking

import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import org.scalatest.freespec.AnyFreeSpec
import domaindocs4s.output.Glossary
import domaindocs4s.utils.SnapshotTest

class GlossaryTest extends AnyFreeSpec {

  given DomainDocsContext = TastyContext.fromCurrentProcess()
  private val collector   = new TastyQueryCollector

  private val docs = collector
    .collectSymbols("domaindocs4s.banking")

  "glossary" - {

    "markdown" in {
      val mdGlossary = Glossary.build(docs).asMarkdown
      SnapshotTest.testSnapshot(mdGlossary, "glossary/glossary.md")
    }

    "html" in {
      val htmlGlossary = Glossary.build(docs).asHtml
      SnapshotTest.testSnapshot(htmlGlossary, "glossary/glossary.html")
    }

  }

}
