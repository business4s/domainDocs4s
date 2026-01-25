package domaindocs4s.banking.party

// start_annotations
import domaindocs4s.domainDoc

case class ContactDetails(
    phoneNumber: ContactDetails.PhoneNumber,
    emailAddress: ContactDetails.EmailAddress,
)

@domainDoc(
  description = "Contact information for a party, including phone numbers, email addresses, or other communication channels.",
  relation = hasOneToMany,
)
object ContactDetails {

  @domainDoc(
    description = "Represents a phone number in the international format.",
    relation = hasOneToManyOptional,
  )
  final case class PhoneNumber(prefix: Int, number: Int) {

    @domainDoc("Return PhoneNumber in a readable format.", "showPhoneNumber")
    def show(): String = s"+$prefix $number"

  }

  @domainDoc(
    description = "Represents an email address as a local-part and a domain.",
    relation = hasOneToManyOptional,
  )
  final case class EmailAddress(localPart: String, domain: String) {

    @domainDoc("Create an email address from a raw text.", "emailAddressFromText")
    def apply(emailAddress: String): EmailAddress = {
      val Array(localPart, domain) = emailAddress.split('@')
      EmailAddress(localPart, domain)
    }

    @domainDoc("Return EmailAddress in a readable format.", "showEmailAddress")
    def show(): String = s"$localPart@$domain"

  }

}
// end_annotations
