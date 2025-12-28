package domaindocs4s.macros

import scala.quoted.{Expr, Quotes, Type}

object Literals {
  
  transparent inline def ensureLiteral[T](inline value: T): T = {
    ${ ensureLiteralImpl('value) }
  }

  private def ensureLiteralImpl[T: Type](value: Expr[T])(using quotes: Quotes): Expr[T] = {
    import quotes.reflect._

    def unwrap(t: Term): Term = t match {
      case Inlined(_, _, t) => unwrap(t)
      case Typed(t, _) => unwrap(t)
      case Block(Nil, t) => unwrap(t)
      case _ => t
    }

    val term = unwrap(value.asTerm)

    term match
      case Literal(_) =>
        value
      case _ =>
        report.errorAndAbort(
          s"""Expected a literal (Literal(Constant(...))).
             |Got (pretty): ${term.show}
             |Got (structure): ${term.show(using Printer.TreeStructure)}
             |""".stripMargin
        )
  }

}
