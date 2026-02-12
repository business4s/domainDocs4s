package domaindocs4s.banking.application

import domaindocs4s.banking.common.*
import domaindocs4s.banking.party.*
import domaindocs4s.domainDoc

import java.time.LocalDateTime
import java.util.UUID

// start_application
@domainDoc("Loan application submitted by the customer for processing.")
final case class Application(
    id: Application.Id,
    applicant: Party,
    income: Option[Income],
    liabilities: List[Liability],
    terms: Application.LoanTerms,
    status: ApplicationStatus,
    submittedAt: LocalDateTime,
)

object Application {

  @domainDoc("Unique technical identifier of a loan application.", "ApplicationId")
  final case class Id(value: UUID)

  @domainDoc("Requested loan terms, including amount, duration, and purpose.")
  final case class LoanTerms(amount: Money, durationInMonths: Int, purpose: String)

}

@domainDoc("Current status of the loan application.")
enum ApplicationStatus {
  case Submitted
  case Approved
  case Rejected(reason: String)
  case Disbursed
}
// end_application
