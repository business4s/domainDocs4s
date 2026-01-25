package domaindocs4s.errors

import tastyquery.Trees.Tree

final case class DomainDocsArgError(label: String, symbol: String, tree: Tree)
    extends RuntimeException(
      s"@domainDoc annotation: argument '$label' on $symbol must be a constant string literal: $tree",
    )
