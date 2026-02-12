package domaindocs4s.banking

import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import domaindocs4s.output.diagram.Diagram
import domaindocs4s.output.glossary.Glossary
import org.scalatest.freespec.AnyFreeSpec
import domaindocs4s.utils.SnapshotTest

class BankingTest extends AnyFreeSpec {

  given DomainDocsContext = TastyContext.fromCurrentProcess()
  private val collector   = new TastyQueryCollector

  private val bankingDocs = collector
    .collectSymbols("domaindocs4s.banking")

  private val applicationDocs = collector
    .collectSymbols("domaindocs4s.banking.application")

  "glossary" - {

    "markdown" in {
      val mdGlossary = Glossary.build(bankingDocs).asMarkdown
      SnapshotTest.testSnapshot(mdGlossary, "banking/glossary.md")
    }

    "html" in {
      val htmlGlossary = Glossary.build(bankingDocs).asHtml
      SnapshotTest.testSnapshot(htmlGlossary, "banking/glossary.html")
    }

  }

  "diagram" in {
    val diagram = Diagram.build(applicationDocs).asMarkdown()
    SnapshotTest.testSnapshot(diagram, "banking/diagram.md")
  }

}
