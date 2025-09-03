package domaindocs4s.banking.party

import domaindocs4s.domainDoc

@domainDoc("Address details declared by the customer.")
final case class Address(
    country: Address.Country,
    city: Address.City,
    postalCode: Address.PostalCode,
    street: Address.Street,
    houseNumber: Address.HouseNumber,
    suiteNumber: Option[Address.SuiteNumber],
)

object Address {

  @domainDoc("Country of the customer's address.")
  final case class Country(value: String)

  @domainDoc("City of the customer's address.")
  final case class City(value: String)

  @domainDoc("Postal code of the customer's address.")
  final case class PostalCode(value: String)

  @domainDoc("Street name of the customer's address.")
  final case class Street(value: String)

  @domainDoc("House number of the customer's address.")
  final case class HouseNumber(value: String)

  @domainDoc("Suite, apartment, or flat number of the customer's address.")
  final case class SuiteNumber(value: String)
}
