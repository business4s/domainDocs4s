package domaindocs4s.domain

import scala.language.implicitConversions
import scala.quoted.{Expr, Quotes, Type}

case class LiteralString(value: String)

object LiteralString {

  implicit transparent inline def stringToLiteralString(inline s: String): LiteralString =
    LiteralString.fromLiteral(s)

  private transparent inline def fromLiteral(inline s: String): LiteralString = {
    ensureLiteral(s)
    LiteralString(s)
  }

  private transparent inline def ensureLiteral[T](inline value: T): Unit = {
    ${ ensureLiteralImpl('value) }
  }

  private def ensureLiteralImpl[T: Type](value: Expr[T])(using quotes: Quotes): Expr[Unit] = {
    import quotes.reflect._

    def unwrap(term: Term): Term = term match {
      case Inlined(_, _, t) => unwrap(t)
      case Typed(t, _)      => unwrap(t)
      case Block(_, t)      => unwrap(t)
      case TypeApply(t, _)  => unwrap(t)
      case _                => term
    }

    val term = unwrap(value.asTerm)

    term match {
      case Literal(_) =>
        '{ () }
      case _          =>
        report.errorAndAbort(
          s"""Expected a literal (Literal(Constant(...))).
             |Got (pretty): ${term.show}
             |Got (structure): ${term.show(using Printer.TreeStructure)}
             |""".stripMargin,
        )
    }
  }

}
