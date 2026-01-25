package domaindocs4s.domain

trait RelationType {
  def left: String
  def right: String
}

case object ExactlyOne extends RelationType {
  def left: String  = "||"
  def right: String = "||"
}

case object ZeroOrOne extends RelationType {
  def left: String  = "|o"
  def right: String = "o|"
}

case object ZeroOrMore extends RelationType {
  def left: String  = "}o"
  def right: String = "o{"
}

case object OneOrMore extends RelationType {
  def left: String  = "}|"
  def right: String = "|{"
}
