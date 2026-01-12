package domaindocs4s.order

// start_imports
import domaindocs4s.domainDoc
// end_imports

object DocumentedDomain {

  // start_annotations
  @domainDoc("Unique technical identifier of an Order", "OrderId")
  final case class Id(value: Int)

  @domainDoc("Business lifecycle state of an Order")
  enum OrderStatus {
    case Draft, Paid, Cancelled
  }

  @domainDoc("Aggregate root representing a customer Order")
  final case class Order(id: Id, status: OrderStatus)
  // end_annotations

}
