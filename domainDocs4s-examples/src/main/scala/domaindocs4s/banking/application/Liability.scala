package domaindocs4s.banking.application

import domaindocs4s.banking.common.*
import domaindocs4s.domainDoc

// start_application
@domainDoc("Customer's declared liability, including amount, duration, and type.")
final case class Liability(
    amount: Money,
    durationInMonths: Int,
    liabilityType: LiabilityType,
)

@domainDoc("Category of the customer's liability.")
enum LiabilityType {
  case Loan
  case Mortgage
  case CreditCard
  case Leasing
}
// end_application
