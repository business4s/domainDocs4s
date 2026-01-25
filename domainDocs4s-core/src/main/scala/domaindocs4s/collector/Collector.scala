package domaindocs4s.collector

import domaindocs4s.domain.{LinkType, Relation, RelationType}
import domaindocs4s.errors.DomainDocsArgError
import tastyquery.Contexts.Context
import tastyquery.Symbols
import tastyquery.Symbols.{ClassSymbol, DeclaringSymbol, Symbol, TermSymbol}
import tastyquery.Trees.*
import tastyquery.Names.TermName

import scala.collection.mutable.ListBuffer

case class DocumentedSymbol(
    nameOverride: Option[String],
    description: Option[String],
    fields: Vector[TermSymbol],
    methods: Vector[TermSymbol],
    relation: Option[Relation],
    symbol: Symbol,
    path: Vector[DeclaringSymbol],
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
      case Inlined(t, _, _)                   => unwrap(t)
      case Typed(t, _)                        => unwrap(t)
      case Block(_, t)                        => unwrap(t)
      case TypeApply(t, _)                    => unwrap(t)
      case Apply(Select(ident, _), List(arg)) => unwrap(arg) // heuristic for wrapper LiteralString(<arg>)
      case NamedArg(_, t)                     => unwrap(t)
      case _                                  => tree
    }

    def getFields(symbol: Symbol): Vector[TermSymbol] = symbol match {
      case sym: ClassSymbol =>
        sym.declarations.collect {
          case t: TermSymbol if t.isParamAccessor && !t.isSynthetic => t
        }.toVector
      case _                => Vector()
    }

    def getMethods(symbol: Symbol): Vector[TermSymbol] = symbol match {
      case sym: ClassSymbol =>
        sym.declarations.collect {
          case t: TermSymbol if t.isMethod && !t.isSynthetic => t
        }.toVector
      case _                => Vector()
    }

    def getConstArg(tree: Tree, label: String): Option[String] = unwrap(tree) match {
      case i: Ident                                   => Some(i.tpe.showBasic.stripSuffix(".type"))
      case Literal(constant)                          => Some(constant.stringValue)
      case Select(_, termName) if isDefault(termName) => None // default argument
      case tree                                       => throw DomainDocsArgError(label, symbol.displayFullName, tree)
    }

    // TODO: Is it safe to use reflection for this? Will the target class be available on classpath in the user's project?
    def loadModuleInstance[A](fullName: String): Option[A] =
      try {
        val cls    = Class.forName(fullName + "$")
        val module = cls.getField("MODULE$").get(null)
        Some(module.asInstanceOf[A])
      } catch {
        case _: Throwable => None
      }

    def getRelationArg(tree: Tree, label: String): Option[Relation] = unwrap(tree) match {
      case Apply(Select(ident, _), List(left, link, right, text)) =>
        for {
          leftEnd          <- getConstArg(left, s"$label.left")
          linkType         <- getConstArg(link, s"$label.link")
          rightEnd         <- getConstArg(right, s"$label.right")
          relText          <- getConstArg(text, s"$label.text")
          leftEndInstance  <- loadModuleInstance[RelationType](leftEnd)
          linkInstance     <- loadModuleInstance[LinkType](linkType)
          rightEndInstance <- loadModuleInstance[RelationType](rightEnd)
        } yield Relation(leftEndInstance, linkInstance, rightEndInstance, relText)
      case Select(_, termName) if isDefault(termName)             => None // default argument
      case tree                                                   => throw DomainDocsArgError(label, symbol.displayFullName, tree)
    }

    def isDefault(name: TermName): Boolean =
      name.toString.startsWith(s"<init>$$default$$")

    symbol match {
      case ts: TermSymbol if ts.isModuleVal =>
        None
      case _                                =>
        symbol
          .getAnnotation(domainDocAnnotation)
          .map { annot =>
            DocumentedSymbol(
              nameOverride = getConstArg(annot.arguments(1), "nameOverride"),
              description = getConstArg(annot.arguments(0), "description"),
              fields = getFields(symbol),
              methods = getMethods(symbol),
              relation = getRelationArg(annot.arguments(2), "relation"),
              symbol = symbol,
              path = path,
            )
          }
    }
  }
}
