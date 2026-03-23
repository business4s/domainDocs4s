package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*

/** Debug utility for inspecting TASTy trees when developing new scanners.
  *
  * Usage from a test or REPL:
  * {{{
  * given ctx: Context = TastyContext.fromCurrentProcess()
  * TastyDebug.dumpMethod("domaindocs4s.architecture.lineage.example.pekko", "QueryBasedProjection", "createReader")
  * }}}
  */
object TastyDebug {

  /** Dump the AST of a specific method body as an indented tree. */
  def dumpMethod(packageName: String, className: String, methodName: String)(using ctx: Context): String = {
    val pkg        = ctx.findPackage(packageName)
    val allClasses = TastyUtils.userClasses(pkg) ++ TastyUtils.moduleClasses(pkg)
    val cls        = allClasses
      .find(c => c.name.toString.stripSuffix("$") == className)
      .getOrElse(
        throw new IllegalArgumentException(s"Class $className not found in $packageName. Available: ${allClasses.map(_.name).mkString(", ")}"),
      )

    val method = cls.declarations
      .collectFirst {
        case ts: TermSymbol if ts.name.toString == methodName =>
          ts.tree.collectFirst { case d: DefDef => d }
      }
      .flatten
      .getOrElse(
        throw new IllegalArgumentException(s"Method $methodName not found in $className. Available: ${cls.declarations.map(_.name).mkString(", ")}"),
      )

    val sb = new StringBuilder
    sb.append(s"--- $className.$methodName ---\n\n")

    sb.append("=== Multiline (Scala-like) ===\n")
    sb.append(method.showMultiline)
    sb.append("\n\n")

    sb.append("=== AST nodes (type + name) ===\n")
    method.rhs.foreach { rhs =>
      val traverser = new TreeTraverser {
        var depth                               = 0
        override def traverse(tree: Tree): Unit = {
          val indent = "  " * depth
          val desc   = tree match {
            case Ident(name)     => s"Ident(${TastyUtils.simpleName(name)})"
            case Select(_, name) => s"Select(_, ${TastyUtils.simpleName(name)})  [raw: $name]"
            case Apply(_, _)     => "Apply(...)"
            case TypeApply(_, _) => "TypeApply(...)"
            case Literal(c)      => s"Literal($c)"
            case _: DefDef       => s"DefDef"
            case _: ValDef       => s"ValDef"
            case _: TypeTree     => s"TypeTree"
            case other           => other.getClass.getSimpleName
          }
          sb.append(s"$indent$desc\n")
          depth += 1
          super.traverse(tree)
          depth -= 1
        }
      }
      traverser.traverse(rhs)
    }
    sb.toString()
  }
}
