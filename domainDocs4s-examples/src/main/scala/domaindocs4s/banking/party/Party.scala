package domaindocs4s.banking.party

// start_annotations
import domaindocs4s.banking.party.Party.IdentityDoc
import domaindocs4s.banking.party.Party.IdentityDoc.IdentityDocType
import domaindocs4s.domainDoc

import java.time.LocalDate
import java.util.UUID

@domainDoc(
  description = "Represents a party involved in the loan process, such as a customer, employee, or supplier",
  relation = hasOneToMany,
)
final case class Party(
    id: Party.Id,
    firstName: Party.FirstName,
    lastName: Party.LastName,
    personalNumber: Party.PersonalNumber,
    identityDocType: IdentityDoc,
    address: Address,
    contactDetails: ContactDetails,
) {

  @domainDoc("Updates the party's address.")
  def updateAddress(): Unit = ()

}

object Party {

  @domainDoc(
    description = "Unique technical identifier of a party.",
    name = "PartyId",
    relation = identifiesByOne,
  )
  final case class Id(value: UUID)

  @domainDoc(
    description = "First name of the party.",
    relation = identifiesByOne,
  )
  final case class FirstName(value: String)

  @domainDoc(
    description = "Last name of the party.",
    relation = identifiesByOne,
  )
  final case class LastName(value: String)

  @domainDoc(
    description = "National personal identification number of the party.",
    relation = identifiesByOne,
  )
  final case class PersonalNumber(value: String)

  @domainDoc(
    description = "Details from the customer's identity document, including number, type, and expiration date.",
    name = "IdentityDocument",
    relation = identifiesByMany,
  )
  final case class IdentityDoc(
      number: String,
      identityDocType: IdentityDocType,
      expirationDate: LocalDate,
  )

  @domainDoc(name = "IdentityDocument")
  object IdentityDoc {
    @domainDoc(
      description = "Category of the customer's identity document.",
      name = "IdentityDocumentType",
      relation = isTypeOf,
    )
    enum IdentityDocType {
      case ID
      case Passport
      case ResidenceCard
    }
  }

}
// end_annotations
