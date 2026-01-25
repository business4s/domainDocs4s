package domaindocs4s.banking

package object party {

  // start_utils
  import domaindocs4s.domain._

  transparent inline def hasOneToMany: Relation = Relation(
    left = ExactlyOne,
    link = Identifying,
    right = OneOrMore,
    text = "has",
  )

  transparent inline def hasOneToManyOptional: Relation = Relation(
    left = ExactlyOne,
    link = Identifying,
    right = ZeroOrMore,
    text = "has",
  )

  transparent inline def isTypeOf: Relation = Relation(
    left = ZeroOrMore,
    link = Identifying,
    right = ExactlyOne,
    text = "is type of",
  )

  transparent inline def identifiesByOne: Relation = Relation(
    left = ExactlyOne,
    link = Identifying,
    right = ExactlyOne,
    text = "identifies by",
  )

  transparent inline def identifiesByMany: Relation = Relation(
    left = ExactlyOne,
    link = Identifying,
    right = OneOrMore,
    text = "identifies by",
  )
  // end_utils

}
