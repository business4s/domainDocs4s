package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*
import tastyquery.Types.*

import scala.collection.mutable.ListBuffer

// ============================================================================
// TASTy-based Call Graph Extractor
//
// Generic infrastructure — not doobie-specific.
// Walks compiled classes via TASTy and extracts:
//   - Method declarations per class
//   - Method-to-method call relationships (field.method() patterns)
// ============================================================================

class TastyCallGraphExtractor(using ctx: Context) {

  def extract(packageName: String): List[ExtractedMethod] = {
    val classes = TastyUtils.userClasses(ctx.findPackage(packageName))

    classes.flatMap { cls =>
      val className = cls.name.toString
      val fieldTypes = resolveFieldTypes(cls)

      cls.declarations.collect {
        case ts: TermSymbol if isUserMethod(ts) =>
          val calls = extractCalls(ts, fieldTypes)
          ExtractedMethod(className, packageName, ts.name.toString, calls)
      }
    }
  }

  private def resolveFieldTypes(cls: ClassSymbol): Map[String, String] =
    cls.declarations.collect {
      case ts: TermSymbol if !isUserMethod(ts) && !ts.name.toString.startsWith("<") =>
        ts.declaredType match {
          case tr: TypeRef => Some(ts.name.toString -> tr.name.toString)
          case _           => None
        }
    }.flatten.toMap

  private def extractCalls(ts: TermSymbol, fieldTypes: Map[String, String]): List[MethodRef] =
    ts.tree match {
      case Some(defDef: DefDef) =>
        defDef.rhs.toList.flatMap { rhs =>
          val collector = new MethodCallCollector(fieldTypes)
          collector.traverse(rhs)
          collector.calls.distinct
        }
      case _ => Nil
    }

  private def isUserMethod(ts: TermSymbol): Boolean = {
    val name = ts.name.toString
    !name.startsWith("<") &&
    !name.startsWith("_") &&
    !name.startsWith("copy") &&
    !name.startsWith("product") &&
    name != "equals" &&
    name != "hashCode" &&
    name != "toString" &&
    name != "canEqual" &&
    name != "writeReplace" &&
    ts.tree.exists(_.isInstanceOf[DefDef]) &&
    !ts.isSynthetic
  }

  private class MethodCallCollector(fieldTypes: Map[String, String]) extends TreeTraverser {
    val calls: ListBuffer[MethodRef] = ListBuffer.empty

    override def traverse(tree: Tree): Unit = {
      tree match {
        case Apply(Select(Ident(fieldName), methodName), _) =>
          addIfKnown(fieldName.toString, methodName.toString)
        case Apply(TypeApply(Select(Ident(fieldName), methodName), _), _) =>
          addIfKnown(fieldName.toString, methodName.toString)
        case _ =>
      }
      super.traverse(tree)
    }

    private def addIfKnown(fieldName: String, methodName: String): Unit =
      fieldTypes.get(fieldName).foreach { className =>
        calls += MethodRef(className, methodName.takeWhile(_ != '['))
      }
  }
}
