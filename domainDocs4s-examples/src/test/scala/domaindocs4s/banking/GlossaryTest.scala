package domaindocs4s.banking

import domaindocs4s.collector.TastyQueryCollector
import org.scalatest.freespec.AnyFreeSpec
import domaindocs4s.output.Glossary
import domaindocs4s.utils.{SnapshotTest, TestClasspath}
import tastyquery.Contexts.Context
import tastyquery.jdk.ClasspathLoaders

class GlossaryTest extends AnyFreeSpec {

  given Context = Context.initialize(ClasspathLoaders.read(TestClasspath.current))

  private val collector = new TastyQueryCollector

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
