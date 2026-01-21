package domaindocs4s.banking.application

import domaindocs4s.banking.common.*
import domaindocs4s.banking.party.*
import domaindocs4s.domainDoc

import java.time.LocalDateTime
import java.util.UUID

@domainDoc("Loan application submitted by the customer for processing.")
final case class Application(
    id: Application.Id,
    applicant: Party,
    income: List[Income],
    liability: List[Liability],
    terms: Application.LoanTerms,
    status: ApplicationStatus,
    submittedAt: LocalDateTime,
) {

  @domainDoc("Marks the loan application as submitted by the customer.")
  def submit(): Unit = ()

  @domainDoc("Approves the loan application after successful review.")
  def approve(): Unit = ()

  @domainDoc("Rejects the loan application, preventing further processing.")
  def reject(): Unit = ()

  @domainDoc("Disburses the approved loan amount to the customer.")
  def disburse(): Unit = ()

}

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
