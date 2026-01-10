package domaindocs4s.collector

import domaindocs4s.errors.DomainDocsArgError
import tastyquery.{Contexts, Symbols}
import tastyquery.Contexts.Context
import tastyquery.Symbols.{DeclaringSymbol, Symbol, TermSymbol}
import tastyquery.Trees.{Literal, Select}

import scala.collection.mutable.ListBuffer

case class DocumentedSymbol(nameOverride: Option[String], description: Option[String], symbol: Symbol, path: Vector[DeclaringSymbol])

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

  val domainDocAnnotation = ctx.findTopLevelClass("domaindocs4s.domainDoc")

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
    println(s"$symbol - ${symbol.displayFullName}")

    symbol match {
      case ts: TermSymbol if ts.isModuleVal =>
        None
      case _                                =>
        symbol
          .getAnnotation(domainDocAnnotation)
          .map(annot => {
            def getConstArg(index: Int, label: String): Option[String] = {
              annot.arguments(index) match {
                case Literal(constant)                                                            => Some(constant.stringValue)
                case Select(_, termName) if termName.toString == s"<init>$$default$$${index + 1}" => None
                case _                                                                            => throw DomainDocsArgError(label, symbol.displayFullName)
              }
            }

            DocumentedSymbol(
              nameOverride = getConstArg(1, "nameOverride"),
              description = getConstArg(0, "description"),
              symbol = symbol,
              path = path,
            )
          })
    }
  }
}
