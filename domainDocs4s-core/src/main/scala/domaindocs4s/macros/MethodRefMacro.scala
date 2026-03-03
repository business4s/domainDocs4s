package domaindocs4s.macros

import scala.quoted.*

object MethodRefMacro {

  /** Extract (className, methodName) from a `_.methodName` lambda at compile time. */
  inline def extract[T](inline selector: T => Any): (String, String) =
    ${ extractImpl[T]('selector) }

  private def extractImpl[T: Type](selector: Expr[T => Any])(using Quotes): Expr[(String, String)] = {
    import quotes.reflect.*

    val className = TypeRepr.of[T].typeSymbol.name
    val methodName = extractMethodName(selector.asTerm)

    Expr((className, methodName))
  }

  private def extractMethodName(using Quotes)(tree: quotes.reflect.Tree): String = {
    import quotes.reflect.*
    tree match {
      case Inlined(_, _, body)                        => extractMethodName(body)
      case Block(List(DefDef(_, _, _, Some(body))), _) => extractMethodName(body)
      case Select(_, name)                            => name
      case Apply(fun, _)                              => extractMethodName(fun)
      case TypeApply(fun, _)                          => extractMethodName(fun)
      case _ =>
        report.errorAndAbort(s"Expected _.methodName, got: ${tree.show}")
    }
  }
}
