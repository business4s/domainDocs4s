package domaindocs4s.banking.common

import domaindocs4s.domainDoc

@domainDoc("Money value with amount and currency. Defaults to PLN if not specified.")
final case class Money(
    amount: BigDecimal,
    currency: Currency = Currency.PLN,
)

@domainDoc("Currency supported in the loan application process.")
enum Currency {
  case PLN
  case USD
  case EUR
}
