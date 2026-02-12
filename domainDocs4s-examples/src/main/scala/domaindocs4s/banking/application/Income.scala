package domaindocs4s.banking.application

import domaindocs4s.banking.common.*
import domaindocs4s.domainDoc

// start_application
@domainDoc("Customer's declared income, including monthly amount and source.")
final case class Income(
    monthlyAmount: Money,
    source: IncomeSource,
)

@domainDoc("Type of the customer's primary income source.")
enum IncomeSource {
  case Employment
  case SelfEmployment
  case Rental
  case Investment
  case Pension
  case Other
}
// end_application
