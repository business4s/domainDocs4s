package domaindocs4s.errors

final case class DomainDocsArgError(label: String, symbol: String)
    extends RuntimeException(
      s"@domainDoc annotation: argument '$label' on $symbol must be a constant string literal",
    )
