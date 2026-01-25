package domaindocs4s.banking.party

// start_annotations
import domaindocs4s.domainDoc

@domainDoc(
  description = "Address details declared by the customer.",
  relation = hasOneToMany,
)
final case class Address(
    country: Address.Country,
    city: Address.City,
    postalCode: Address.PostalCode,
    street: Address.Street,
    houseNumber: Address.HouseNumber,
    suiteNumber: Option[Address.SuiteNumber],
)

object Address {

  @domainDoc(
    description = "Country of the customer's address.",
    relation = hasOneToMany,
  )
  final case class Country(value: String)

  @domainDoc(
    description = "City of the customer's address.",
    relation = hasOneToMany,
  )
  final case class City(value: String)

  @domainDoc(
    description = "Postal code of the customer's address.",
    relation = hasOneToMany,
  )
  final case class PostalCode(value: String)

  @domainDoc(
    description = "Street name of the customer's address.",
    relation = hasOneToManyOptional,
  )
  final case class Street(value: String)

  @domainDoc(
    description = "House number of the customer's address.",
    relation = hasOneToMany,
  )
  final case class HouseNumber(value: String)

  @domainDoc(
    description = "Suite, apartment, or flat number of the customer's address.",
    relation = hasOneToManyOptional,
  )
  final case class SuiteNumber(value: String)
}
// end_annotations
