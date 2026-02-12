package domaindocs4s.banking.party

import domaindocs4s.domainDoc

import java.time.LocalDate

@domainDoc("Details from the customer's identity document, including number, type, and expiration date.", "IdentityDocument")
final case class IdentityDoc(
    number: String,
    identityDocType: IdentityDocType,
    expirationDate: LocalDate,
)

@domainDoc("Category of the customer's identity document.", "IdentityDocumentType")
enum IdentityDocType {
  case ID
  case Passport
  case ResidenceCard
}
