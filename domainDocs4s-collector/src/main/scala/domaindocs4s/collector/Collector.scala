package domaindocs4s.collector

import tastyquery.{Contexts, Symbols}
import tastyquery.Contexts.Context
import tastyquery.Symbols.{DeclaringSymbol, Symbol}

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
    symbol
      .getAnnotation(domainDocAnnotation)
      .map(annot =>
        DocumentedSymbol(
          // TODO if args are not constant, this should raise an exception
          Option.when(annot.argCount >= 2)(annot.argIfConstant(1).map(_.stringValue)).flatten,
          Option.when(annot.argCount >= 1)(annot.argIfConstant(0).map(_.stringValue)).flatten,
          symbol,
          path,
        ),
      )
  }

}
