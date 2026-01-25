package domaindocs4s.output.diagram

enum Direction(val code: String) {
  case TB   extends Direction("direction TB")
  case BT   extends Direction("direction BT")
  case LR   extends Direction("direction LR")
  case RL   extends Direction("direction RL")
  case None extends Direction("")
}
