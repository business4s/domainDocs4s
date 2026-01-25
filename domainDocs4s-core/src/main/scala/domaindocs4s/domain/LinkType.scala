package domaindocs4s.domain

trait LinkType {
  def value: String
}

case object Identifying extends LinkType {
  def value: String = "--"
}

case object NonIdentifying extends LinkType {
  def value: String = ".."
}
