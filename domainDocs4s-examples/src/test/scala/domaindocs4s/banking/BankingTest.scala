package domaindocs4s.banking

import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import domaindocs4s.output.diagram.{Diagram, Direction}
import domaindocs4s.output.glossary.Glossary
import org.scalatest.freespec.AnyFreeSpec
import domaindocs4s.utils.SnapshotTest

class BankingTest extends AnyFreeSpec {

  given DomainDocsContext = TastyContext.fromCurrentProcess()
  private val collector   = new TastyQueryCollector

  private val docs = collector
    .collectSymbols("domaindocs4s.banking")

  private val partyDocs = collector
    .collectSymbols("domaindocs4s.banking.party")

  "glossary" - {

    "markdown" in {
      val mdGlossary = Glossary.build(docs).asMarkdown
      SnapshotTest.testSnapshot(mdGlossary, "banking/glossary.md")
    }

    "html" in {
      val htmlGlossary = Glossary.build(docs).asHtml
      SnapshotTest.testSnapshot(htmlGlossary, "banking/glossary.html")
    }

  }

  "diagram" - {

    "markdown" in {
      val diagram = Diagram.build(partyDocs).asMarkdown(Direction.LR)
      SnapshotTest.testSnapshot(diagram, "banking/diagram.md")
    }

  }

}
