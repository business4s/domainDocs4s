package domaindocs4s.macros

import scala.quoted.*

object TypeFqnMacro {

  /** Extract the fully qualified name of a type at compile time.
    *
    * Works with opaque types, type aliases, classes, and traits. Does NOT use ClassTag — preserves type alias and opaque type names that ClassTag would
    * erase.
    */
  inline def fqn[T]: String = ${ fqnImpl[T] }

  private def fqnImpl[T: Type](using Quotes): Expr[String] = {
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym  = tpe.typeSymbol
    // Strip `$` from module class names (e.g., `Transactors$` → `Transactors`)
    val ownerFqn = sym.owner.fullName.split('.').map(_.stripSuffix("$")).mkString(".")
    val fqn      = ownerFqn + "." + sym.name
    Expr(fqn)
  }
}
