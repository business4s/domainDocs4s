package domaindocs4s.collector

import tastyquery.Contexts.Context
import tastyquery.jdk.ClasspathLoaders

import java.nio.file.Path

object Main {

  def main(args: Array[String]): Unit = {

    val cp        = ClasspathLoaders.read(
      List(
        Path.of("/Users/krever/Projects/priv/domainDocs4s/domainDocs4s-api/target/scala-3.5.1/classes"),
        Path.of("/Users/krever/Projects/priv/domainDocs4s/domainDocs4s-test-input/target/scala-3.5.1/classes"),
      ),
    )
    given Context = Context.initialize(cp)

    val collector = new TastyQueryCollector

    val docs = collector
      .collectSymbols("domaindocs4stest")

    docs.symbols
      .foreach(println)

    def printTree(s: DocumentationTree, indent: Int = 0): Unit = {
      println(s.symbol.symbol.toString.indent(indent))
      s.children.foreach(printTree(_, indent + 1))
    }
    docs.asTree.foreach(printTree(_, 0))
  }
}