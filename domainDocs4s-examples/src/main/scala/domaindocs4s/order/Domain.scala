package domaindocs4s.order

object Domain {
  
  // start_domain
  final case class Id(value: Int)

  enum OrderStatus:
    case Draft, Paid, Cancelled

  final case class Order(id: Id, status: OrderStatus)
  // end_domain
  
}

