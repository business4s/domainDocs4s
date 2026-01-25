package domaindocs4s.custom

import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import domaindocs4s.custom.CustomGenerator
import org.scalatest.freespec.AnyFreeSpec
import domaindocs4s.utils.SnapshotTest

class CustomTest extends AnyFreeSpec {

  given DomainDocsContext = TastyContext.fromCurrentProcess()
  private val collector   = new TastyQueryCollector

  private val docs = collector
    .collectSymbols("domaindocs4s.custom")

  "custom" - {

    "markdown" in {
      val custom = CustomGenerator.build(docs).asMarkdown()
      SnapshotTest.testSnapshot(custom, "custom/custom.md")
    }

  }

}
