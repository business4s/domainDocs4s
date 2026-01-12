| Parent          | Name                 | Description                                                                                                 |
|-----------------|----------------------|-------------------------------------------------------------------------------------------------------------|
|                 | Address              | Address details declared by the customer.                                                                   |
|                 | Application          | Loan application submitted by the customer for processing.                                                  |
| Application     | approve              | Approves the loan application after successful review.                                                      |
| Application     | disburse             | Disburses the approved loan amount to the customer.                                                         |
| Application     | reject               | Rejects the loan application, preventing further processing.                                                |
| Application     | submit               | Marks the loan application as submitted by the customer.                                                    |
|                 | ApplicationId        | Unique technical identifier of a loan application.                                                          |
|                 | ApplicationStatus    | Current status of the loan application.                                                                     |
|                 | City                 | City of the customer's address.                                                                             |
|                 | ContactDetails$      | Contact information for a party, including phone numbers, email addresses, or other communication channels. |
| ContactDetails$ | EmailAddress         | Represents an email address as a local-part and a domain.                                                   |
| EmailAddress    | emailAddressFromText | Create an email address from a raw text.                                                                    |
| EmailAddress    | showEmailAddress     | Return EmailAddress in a readable format.                                                                   |
| ContactDetails$ | PhoneNumber          | Represents a phone number in the international format.                                                      |
| PhoneNumber     | showPhoneNumber      | Return PhoneNumber in a readable format.                                                                    |
|                 | Country              | Country of the customer's address.                                                                          |
|                 | Currency             | Currency supported in the loan application process.                                                         |
|                 | FirstName            | First name of the party.                                                                                    |
|                 | HouseNumber          | House number of the customer's address.                                                                     |
|                 | IdentityDocument     | Details from the customer's identity document, including number, type, and expiration date.                 |
|                 | IdentityDocumentType | Category of the customer's identity document.                                                               |
|                 | Income               | Customer's declared income, including monthly amount and source.                                            |
|                 | IncomeSource         | Type of the customer's primary income source.                                                               |
|                 | LastName             | Last name of the party.                                                                                     |
|                 | Liability            | Customer's declared liability, including amount, duration, and type.                                        |
|                 | LiabilityType        | Category of the customer's liability.                                                                       |
|                 | LoanTerms            | Requested loan terms, including amount, duration, and purpose.                                              |
|                 | Money                | Money value with amount and currency. Defaults to PLN if not specified.                                     |
|                 | Party                | Represents a party involved in the loan process, such as a customer, employee, or supplier                  |
| Party           | updateAddress        | Updates the party's address.                                                                                |
|                 | PartyId              | Unique technical identifier of a party.                                                                     |
|                 | PersonalNumber       | National personal identification number of the party.                                                       |
|                 | PostalCode           | Postal code of the customer's address.                                                                      |
|                 | Street               | Street name of the customer's address.                                                                      |
|                 | SuiteNumber          | Suite, apartment, or flat number of the customer's address.                                                 |