package domaindocs4s.output.diagram

import domaindocs4s.collector.{Documentation, DocumentedSymbol}
import tastyquery.Contexts.Context
import tastyquery.Symbols.{Symbol, TermSymbol}
import tastyquery.Trees.{DefDef, ValDef}
import tastyquery.Types.{AppliedType, Type, TypeRef}

import java.nio.file.{Files, Path}

case class Diagram(entities: List[Entity]) {

  def asMarkdown(direction: Direction = Direction.None): String = {
    if (entities.isEmpty) {
      s"""
         |```mermaid
         |erDiagram
         |```
         |""".stripMargin
    } else {
      val directionLine = if (direction == Direction.None) "" else s"  direction $direction\n"
      s"""
         |```mermaid
         |erDiagram
         |$directionLine${entities.map(_.asMarkdown).mkString("\n")}
         |```
         |""".stripMargin
    }
  }

}

object Diagram {

  def build(docs: Documentation)(using ctx: Context): Diagram = {
    val documentedSymbols = docs.symbols
    val docEntities       = docs.symbols.collect {
      case sym if sym.symbol.isClass => buildDocEntity(sym, documentedSymbols)(using ctx)
    }
    Diagram(docEntities)
  }

  private def buildDocEntity(sym: DocumentedSymbol, documentedSymbols: List[DocumentedSymbol])(using ctx: Context): Entity = {
    Entity(
      name = sym.name,
      associations = buildAssociations(sym, documentedSymbols)(using ctx),
      fields = buildFields(sym),
    )
  }

  private def buildAssociations(
      sym: DocumentedSymbol,
      documentedSymbols: List[DocumentedSymbol],
  )(using ctx: Context): Map[String, Association] = {
    sym.declarations.flatMap { field =>
      documentedSymbols
        .filter(s => isFieldRelatedToSymbol(field, s.symbol)(using ctx))
        .map(s => (s.name, determineRelationshipType(field)(using ctx)))
    }.toMap
  }

  private def buildFields(sym: DocumentedSymbol): Map[String, String] = {
    sym.declarations.map(f => (f.name.toString, shortTypeName(typeOfTerm(f)))).toMap
  }

  private def isFieldRelatedToSymbol(field: TermSymbol, symbol: Symbol)(using ctx: Context): Boolean = {
    termTypeSymbol(field)(using ctx).contains(symbol) || isTypeArgument(field, symbol)(using ctx)
  }

  private def isTypeArgument(field: TermSymbol, symbol: Symbol)(using ctx: Context): Boolean = typeOfTerm(field) match {
    case at: AppliedType =>
      at.args.exists {
        case tr: TypeRef => tr.optSymbol.contains(symbol)
        case _           => false
      }
    case _               => false
  }

  private def determineRelationshipType(field: TermSymbol)(using ctx: Context): Association = {
    val fieldType       = typeOfTerm(field).dealias
    val optionCls       = ctx.findTopLevelClass("scala.Option")
    val iterableOnceCls = ctx.findTopLevelClass("scala.collection.IterableOnce")

    if (fieldType.baseType(optionCls).isDefined) Association.ZeroOrOne
    else if (fieldType.baseType(iterableOnceCls).isDefined) Association.ZeroOrMore
    else Association.ExactlyOne
  }

  private def typeOfTerm(t: TermSymbol): Type = t.tree match {
    case Some(v: ValDef) => v.tpt.toType
    case Some(d: DefDef) => d.resultTpt.toType
    case Some(other)     => throw new Exception(s"Unexpected tree type for $t: ${other.getClass.getSimpleName}")
    case None            => throw new Exception(s"No tree found for term symbol $t")
  }

  private def termTypeSymbol(t: TermSymbol)(using ctx: Context): Option[Symbol] = typeOfTerm(t).dealias match {
    case tr: TypeRef => tr.optSymbol
    case _           => None
  }

  private def shortTypeName(tp: Type): String =
    tp.showBasic.replaceAll("\\b(?:[A-Za-z_][$\\w]*\\.)+([A-Za-z_][$\\w]*)\\b", "$1")

  def write(docs: String, path: String): Unit = {
    val finalPath = Path.of(path)
    Files.write(finalPath, docs.getBytes)
    println(s"File saved in $finalPath")
  }
}
