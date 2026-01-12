package domaindocs4s.banking

import domaindocs4s.collector.{DomainDocsContext, TastyContext, TastyQueryCollector}
import domaindocs4s.output.Glossary

object Main {

  def main(args: Array[String]): Unit = {

    // 1) Setup collector
    given DomainDocsContext = TastyContext.fromCurrentProcess()
    val collector           = new TastyQueryCollector

    // 2) Collect documentation model
    val docs = collector
      .collectSymbols("domaindocs4s.banking")

    // 3) Generate glossary markdown (or another supported output and format)
    val glossary =
      Glossary
        .build(docs) // build glossary
        .asMarkdown  // render markdown

    // 4) Write the result to file
    Glossary.write(glossary, "glossary.md")
  }
}
