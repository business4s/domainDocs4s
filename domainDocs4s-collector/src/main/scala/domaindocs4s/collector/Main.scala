package domaindocs4s.collector

import domaindocs4s.output.Glossary
import tastyquery.Contexts.Context
import tastyquery.jdk.ClasspathLoaders

import java.nio.file.Path

object Main {

  def main(args: Array[String]): Unit = {

    val cp        = ClasspathLoaders.read(
      List(
        //        Path.of("/Users/krever/Projects/priv/domainDocs4s/domainDocs4s-api/target/scala-3.5.1/classes"),
        //        Path.of("/Users/krever/Projects/priv/domainDocs4s/domainDocs4s-test-input/target/scala-3.5.1/classes"),
        Path.of("/home/bartek/IdeaProjects/domainDocs4s/domainDocs4s-api/target/scala-3.5.1/classes"),
        Path.of("/home/bartek/IdeaProjects/domainDocs4s/domainDocs4s-examples/target/scala-3.5.1/classes"),
      ),
    )
    given Context = Context.initialize(cp)

    val collector = new TastyQueryCollector

    val docs = collector
      .collectSymbols("domaindocs4s.banking")

    def printTree(s: DocumentationTree, indent: Int = 0): Unit = {
      print(s.symbol.symbol.toString.indent(indent))
      s.children.foreach(printTree(_, indent + 2))
    }

    println()
    docs.asTree.foreach(printTree(_))

    val mdGlossary = Glossary.build(docs).asMarkdown
    val htmlGlossary = Glossary.build(docs).asHtml

    Glossary.write(mdGlossary, "./glossary.md")
    Glossary.write(htmlGlossary, "./glossary.html")
  }
}
