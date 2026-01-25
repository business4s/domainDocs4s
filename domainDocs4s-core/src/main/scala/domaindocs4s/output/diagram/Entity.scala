package domaindocs4s.output.diagram

import domaindocs4s.domain.Relation

case class Entity(
    name: String,
    fields: Map[String, String],
    parent: Option[String],
    relation: Option[Relation],
) {

  def asMarkdown(withFields: Boolean): String = {
    def printFields(fields: Map[String, String]): String = {
      fields.map((n, t) => s"$n ${t.replace("(", "").replace(")", "")}").mkString(" {\n  ", "\n  ", "\n}")
    }

    val classMd =
      if (!withFields || fields.isEmpty) s"${name}"
      else s"${name}" + printFields(fields)

    val associations = relation match {
      case None      => ""
      case Some(rel) => parent.map(p => s"\n$p $rel $name : \"${rel.text}\" ${fields.getOrElse(p, "")}").mkString("\n")
    }

    (classMd + associations).replace("$", "")
  }

}
