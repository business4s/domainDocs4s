package domaindocs4s.collector

import domaindocs4s.errors.DomainDocsArgError
import tastyquery.Contexts.Context
import tastyquery.Symbols
import tastyquery.Symbols.{ClassSymbol, DeclaringSymbol, Symbol, TermSymbol}
import tastyquery.Trees.*

import scala.collection.mutable.ListBuffer

case class DocumentedSymbol(
    nameOverride: Option[String],
    description: Option[String],
    symbol: Symbol,
    path: Vector[DeclaringSymbol],
    declarations: Vector[TermSymbol],
) {
  def name: String = nameOverride.getOrElse(symbol.name.toString)
}

case class DocumentationTree(symbol: DocumentedSymbol, children: List[DocumentationTree])

case class Documentation(symbols: List[DocumentedSymbol]) {

  def asTree: List[DocumentationTree] = {
    val builder: Map[DocumentedSymbol, ListBuffer[DocumentedSymbol]] = symbols.map(x => x -> ListBuffer[DocumentedSymbol]()).toMap
    for {
      child  <- symbols
      // TODO not optimal impl
      parent <- child.path.flatMap(pathElem => symbols.find(_.symbol == pathElem)).lastOption
    } builder(parent).append(child)
    val roots                                             = builder.filter(x => !builder.exists(_._2.contains(x))).keys
    def buildTree(s: DocumentedSymbol): DocumentationTree = {
      DocumentationTree(s, builder(s).map(buildTree).toList)
    }
    roots.map(buildTree).toList
  }

}

trait Collector {

  def collectSymbols(packageName: String): Documentation

}

class TastyQueryCollector(using ctx: Context) extends Collector {

  val domainDocAnnotation = ctx.findTopLevelClass("domaindocs4s.domain.domainDoc")

  override def collectSymbols(packageName: String): Documentation = {
    val pkg = ctx.findPackage(packageName)
    Documentation(recurse(pkg, Vector()))
  }

  private def recurse(symbol: Symbol, path: Vector[DeclaringSymbol])(using Context): List[DocumentedSymbol] = {
    val children = symbol match {
      case symbol: Symbols.DeclaringSymbol =>
        val newPath = path.appended(symbol)
        symbol.declarations.flatMap(recurse(_, newPath))
      case _                               => List()
    }
    asDocumented(symbol, path).toList ++ children
  }

  private def asDocumented(symbol: Symbol, path: Vector[DeclaringSymbol]): Option[DocumentedSymbol] = {

    def unwrap(tree: Tree): Tree = tree match {
      case Inlined(t, _, _)               => unwrap(t)
      case Typed(t, _)                    => unwrap(t)
      case Block(_, t)                    => unwrap(t)
      case TypeApply(t, _)                => unwrap(t)
      case Apply(Select(_, _), List(arg)) => unwrap(arg) // heuristic for wrapper LiteralString(<arg>)
      case _                              => tree
    }

    def getDeclarations(symbol: Symbol): Vector[TermSymbol] = {
      symbol match {
        case sym: ClassSymbol =>
          sym.declarations.collect {
            case t: TermSymbol if !t.isSynthetic && t.name.toString != "<init>" => t
          }.toVector
        case _                => Vector()
      }
    }

    symbol match {
      case ts: TermSymbol if ts.isModuleVal =>
        None
      case _                                =>
        symbol
          .getAnnotation(domainDocAnnotation)
          .map(annot => {
            def getConstArg(index: Int, label: String): Option[String] = {
              unwrap(annot.arguments(index)) match {
                case Literal(constant)                                                            => Some(constant.stringValue)
                case Select(_, termName) if termName.toString == s"<init>$$default$$${index + 1}" => None // default argument
                case _                                                                            => throw DomainDocsArgError(label, symbol.displayFullName)
              }
            }

            DocumentedSymbol(
              nameOverride = getConstArg(1, "nameOverride"),
              description = getConstArg(0, "description"),
              symbol = symbol,
              path = path,
              declarations = getDeclarations(symbol),
            )
          })
    }
  }
}
