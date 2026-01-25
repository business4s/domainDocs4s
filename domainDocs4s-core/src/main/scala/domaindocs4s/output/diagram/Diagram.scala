package domaindocs4s.output.diagram

import domaindocs4s.collector.Documentation
import tastyquery.Symbols.{DeclaringSymbol, TermSymbol}

case class Diagram(entries: List[Entity]) {

  def asMarkdown(direction: Direction = Direction.None, withFields: Boolean = true): String =
    s"""
       |```mermaid
       |erDiagram
       |${direction.code}
       |${entries.map(_.asMarkdown(withFields)).mkString("\n")}
       |```
       |""".stripMargin

}

object Diagram {

  def build(docs: Documentation, onlyDocumented: Boolean = false): Diagram = {
    val documented = docs.symbols.map { s => (s.symbol.name.toString, s) }.toMap

    def extract(symbol: TermSymbol): (String, String) = {
      val fullTypeName  = symbol.declaredType.showBasic
      val shortTypeName = fullTypeName.replaceAll("\\b(?:[A-Za-z_][$\\w]*\\.)+([A-Za-z_][$\\w]*)\\b", "$1")
      (symbol.name.toString, shortTypeName)
    }

    def parent(path: Vector[DeclaringSymbol], onlyDocumented: Boolean): Option[String] = {
      val maybeParent = path.lastOption.map(_.name.toString).filter {
        if (onlyDocumented) documented.contains
        else _ => true
      }

      documented.get(maybeParent.getOrElse("")) match {
        case Some(p) => Some(p.nameOverride.getOrElse(p.name))
        case _       => maybeParent
      }
    }

    val entries = docs.symbols.collect {
      case sym if sym.symbol.isClass =>
        Entity(
          name = sym.name,
          fields = sym.fields.map(extract).filter(_._1 != "<init>").toMap,
          parent = parent(sym.path, onlyDocumented),
          relation = sym.relation,
        )
    }

    entries.foreach(println)

    Diagram(entries)
  }

}
