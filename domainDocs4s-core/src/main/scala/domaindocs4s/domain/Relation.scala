package domaindocs4s.domain

case class Relation(left: RelationType, link: LinkType, right: RelationType, text: String) {
  override def toString: String = s"${left.left}${link.value}${right.right}"
}
