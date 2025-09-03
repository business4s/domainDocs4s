package domaindocs4s.output

import domaindocs4s.collector.{Documentation, DocumentedSymbol}
import domaindocs4s.output.Glossary.Entry

import java.nio.file.Path
import java.nio.file.Files

case class Glossary(entries: List[Glossary.Entry]) {

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
  case class Entry(parent: Option[Entry], name: String, description: Option[String]) {
    def asSeq: Seq[String] = Seq(parent.map(_.name).getOrElse(""), name, description.getOrElse(""))
  }

  def build(docs: Documentation): Glossary = {

    val cache = scala.collection.mutable.Map.empty[(String, Option[String]), Glossary.Entry]

    val documentedSymbols = docs.symbols.map(s => s.symbol -> s).toMap

    def upsert(name: String, desc: Option[String], parent: Option[Glossary.Entry]): Glossary.Entry =
      cache
        .getOrElseUpdate(
          (name, parent.map(_.name)),
          Entry(parent, name, desc),
        )

    docs.symbols.map { s =>
      val parentOpt =
        s.path.foldLeft(Option.empty[Glossary.Entry]) { (maybeParent, symbol) =>
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

    Glossary(cache.values.toList)
  }

  def write(docs: String, path: String): Unit = {
    Files.write(Path.of(path), docs.getBytes)
  }
}
