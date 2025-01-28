package domaindocs4s.output

import domaindocs4s.collector.Documentation

case class Glossary(entries: List[Glossary.Entry]) {

  def asMarkdown: String = ???

}

object Glossary {

  case class Entry(name: String, description: Option[String], children: List[Entry])

  def build(docs: Documentation): Glossary = {
    ???
  }


}
