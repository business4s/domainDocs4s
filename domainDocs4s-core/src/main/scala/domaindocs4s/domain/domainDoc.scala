package domaindocs4s.domain

case class domainDoc(
    description: LiteralString | Null = null,
    name: LiteralString | Null = null,
    relation: Relation | Null = null,
) extends scala.annotation.ConstantAnnotation
