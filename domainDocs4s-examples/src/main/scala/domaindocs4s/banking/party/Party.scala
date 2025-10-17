package domaindocs4s.banking.party

import domaindocs4s.domainDoc

import java.util.UUID

@domainDoc("Represents a party involved in the loan process, such as a customer, employee, or supplier")
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

  @domainDoc("Unique technical identifier of a party.", "PartyId")
  final case class Id(value: UUID)

  @domainDoc("First name of the party.")
  final case class FirstName(value: String)

  @domainDoc("Last name of the party.")
  final case class LastName(value: String)

  @domainDoc("National personal identification number of the party.")
  final case class PersonalNumber(value: String)

}
