package domaindocs4s.custom

// start_generator
import domaindocs4s.collector.Documentation
import domaindocs4s.output.diagram.{Diagram, Direction, Entity}

case class CustomGenerator(entries: List[Entity]) {

  def asMarkdown(direction: Direction = Direction.None, withFields: Boolean = true): String =
    s"""
       |```mermaid
       |classDiagram
       |${direction.code}
       |${entries.map(_.asMarkdown(withFields).replaceAll("\"", "")).mkString("\n")}
       |```
       |""".stripMargin

}

object CustomGenerator {
  def build(docs: Documentation): CustomGenerator = {
    CustomGenerator(Diagram.build(docs).entries)
  }
}
// end_generator
