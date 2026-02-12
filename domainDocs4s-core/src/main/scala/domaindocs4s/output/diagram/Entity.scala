package domaindocs4s.output.diagram

case class Entity(
    name: String,
    fields: Map[String, String],
    associations: Map[String, Association],
) {

  def asMarkdown: String = {
    val entityDefinition   = renderEntityDefinition
    val entityAssociations = renderAssociations
    escapeMermaidSpecialChars(entityDefinition + entityAssociations)
  }

  private def renderEntityDefinition: String =
    if (fields.isEmpty) name
    else name + fields.map((fieldName, fieldType) => s"$fieldName $fieldType").mkString(" {\n ", "\n ", "\n}")

  private def renderAssociations: String =
    associations.map((childName, relType) => s"\n$name ${associationAsMermaid(relType)} $childName : has").mkString

  private def associationAsMermaid(rel: Association): String = rel match {
    case Association.ExactlyOne => "||--||"
    case Association.ZeroOrOne  => "||--o|"
    case Association.ZeroOrMore => "||--o{"
  }

  private def escapeMermaidSpecialChars(text: String): String =
    text.replace("\"", "\\\"").replace("$", "")
}
