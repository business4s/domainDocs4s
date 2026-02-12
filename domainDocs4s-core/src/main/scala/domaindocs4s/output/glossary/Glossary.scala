package domaindocs4s.output.glossary

import domaindocs4s.collector.Documentation

case class Glossary(entries: List[Entry]) {

  def asMarkdown: String = {
    val headers   = Seq("Parent", "Name", "Description")
    val rowWidths = headers.indices.map(i => Math.max(headers(i).length, entries.map(_.asSeq(i).length).max))

    def pad(s: String, chars: Int)  = s + " " * (chars - s.length())
    def makeLine(line: Seq[String]) = line.indices.map(i => pad(line(i), rowWidths(i))).mkString("| ", " | ", " |")

    val headLine          = makeLine(headers)
    val headSeparatorLine = headers.indices.map(i => s"-${"-" * rowWidths(i)}-").mkString("|", "|", "|")
    val entryLines        = entries.map(e => makeLine(e.asSeq))

    (Seq(headLine, headSeparatorLine) ++ entryLines).mkString("\n")
  }

  def asHtml: String = {
    val headers = Seq("Parent", "Name", "Description")
    val thead   =
      s"""<thead>
         |<tr>
         |${headers.map(h => s"    <th>$h</th>").mkString("\n")}
         |  </tr>
         |</thead>""".stripMargin

    val tbodyRows =
      entries
        .map { e =>
          val tds = e.asSeq.map(x => s"    <td>$x</td>").mkString("\n")
          s"""  <tr>
             |$tds
             |  </tr>""".stripMargin
        }
        .mkString("\n")

    s"""<table>
       |$thead
       |<tbody>
       |$tbodyRows
       |</tbody>
       |</table>""".stripMargin
  }
}

object Glossary {

  def build(docs: Documentation): Glossary = {

    val cache = scala.collection.mutable.Map.empty[(String, Option[String]), Entry]

    val documentedSymbols = docs.symbols.map(s => s.symbol -> s).toMap

    def upsert(name: String, desc: Option[String], parent: Option[Entry]): Entry =
      cache
        .getOrElseUpdate(
          (name, parent.map(_.name)),
          Entry(parent, name, desc),
        )

    val _ = docs.symbols.map { s =>
      val parentOpt =
        s.path.foldLeft(Option.empty[Entry]) { (maybeParent, symbol) =>
          documentedSymbols.get(symbol) match {
            case Some(docParent) =>
              Some(
                upsert(
                  name = docParent.nameOverride.getOrElse(docParent.symbol.name.toString),
                  desc = docParent.description,
                  parent = maybeParent,
                ),
              )
            case None            =>
              maybeParent
          }
        }
      val leafName  = s.nameOverride.getOrElse(s.symbol.name.toString)
      upsert(leafName, s.description, parentOpt)
    }

    Glossary(cache.values.toList.sorted)
  }
}
