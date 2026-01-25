package domaindocs4s.custom

// start_relations
import domaindocs4s.domain.{LinkType, Relation, RelationType}

case object InheritanceOrRealization extends RelationType {
  def left: String  = "<|"
  def right: String = "|>"
}

case object Composition extends RelationType {
  def left: String  = "*"
  def right: String = "*"
}

case object Aggregation extends RelationType {
  def left: String  = "o"
  def right: String = "o"
}

case object AssociationOrDependency extends RelationType {
  def left: String  = "<"
  def right: String = ">"
}

case object None extends RelationType {
  def left: String  = ""
  def right: String = ""
}

case object Solid extends LinkType {
  def value: String = "--"
}

case object Dashed extends LinkType {
  def value: String = ".."
}

transparent inline def inheritance: Relation = Relation(
  left = InheritanceOrRealization,
  link = Solid,
  right = None,
  text = "inherits from",
)

transparent inline def composition: Relation = Relation(
  left = Composition,
  link = Solid,
  right = None,
  text = "composes",
)

transparent inline def aggregation: Relation = Relation(
  left = Aggregation,
  link = Solid,
  right = None,
  text = "aggregates",
)

transparent inline def association: Relation = Relation(
  left = None,
  link = Solid,
  right = AssociationOrDependency,
  text = "associates with",
)

transparent inline def linkSolid: Relation = Relation(
  left = None,
  link = Solid,
  right = None,
  text = "",
)

transparent inline def dependency: Relation = Relation(
  left = None,
  link = Dashed,
  right = AssociationOrDependency,
  text = "depends on",
)

transparent inline def realization: Relation = Relation(
  left = None,
  link = Dashed,
  right = InheritanceOrRealization,
  text = "realizes",
)

transparent inline def linkDashed: Relation = Relation(
  left = None,
  link = Dashed,
  right = None,
  text = "",
)
// end_relations
