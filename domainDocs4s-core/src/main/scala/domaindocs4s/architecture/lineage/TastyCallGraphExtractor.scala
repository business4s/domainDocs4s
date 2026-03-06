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
    val pkg = ctx.findPackage(packageName)
    val classesWithPkg = TastyUtils.userClassesRecursive(pkg)

    val methods = classesWithPkg.flatMap { case (ownerPkg, cls) =>
      val className = cls.name.toString.stripSuffix("$")
      val pkgName = ownerPkg.fullName.toString
      val fieldTypes = resolveFieldTypes(cls)

      cls.declarations.collect {
        case ts: TermSymbol if isUserMethod(ts) =>
          val calls = extractCalls(ts, fieldTypes)
          ExtractedMethod(className, pkgName, ts.name.toString, calls)
      }
    }

    addInheritanceEdges(classesWithPkg, methods)
  }

  /** For each child class, add edges from parent methods to child methods.
    *
    * When `ChildImpl extends ParentTrait`, and both declare `methodX`,
    * this adds a call `ParentTrait.methodX → ChildImpl.methodX`.
    * This bridges the gap where field types are traits but integrations
    * are discovered on implementation classes.
    */
  private def addInheritanceEdges(
      classesWithPkg: List[(PackageSymbol, ClassSymbol)],
      methods: List[ExtractedMethod],
  ): List[ExtractedMethod] = {
    // Build parentFqn → List[(childPkg, childName)] map
    val parentToChildren: Map[String, List[(String, String)]] =
      classesWithPkg
        .flatMap { case (ownerPkg, cls) =>
          val childPkg = ownerPkg.fullName.toString
          val childName = cls.name.toString.stripSuffix("$")
          try {
            cls.parents.flatMap { parentType =>
              TastyUtils.extractFqn(parentType).map(_ -> (childPkg, childName))
            }
          } catch { case _: Exception => Nil }
        }
        .groupMap(_._1)(_._2)

    // Index: (pkg, className) → Set[methodName] from existing methods
    val methodsByClass: Map[(String, String), Set[String]] =
      methods.groupBy(m => (m.packageName, m.className)).map { case (k, ms) =>
        k -> ms.map(_.methodName).toSet
      }

    // For each parent class that has children, create bridge edges
    val bridgeEntries: List[ExtractedMethod] = classesWithPkg.flatMap { case (ownerPkg, cls) =>
      val parentPkg = ownerPkg.fullName.toString
      val parentName = cls.name.toString.stripSuffix("$")
      val parentFqn = s"$parentPkg.$parentName"
      val parentMethods = methodsByClass.getOrElse((parentPkg, parentName), Set.empty)

      parentToChildren.getOrElse(parentFqn, Nil).flatMap { case (childPkg, childName) =>
        val childMethods = methodsByClass.getOrElse((childPkg, childName), Set.empty)
        // Methods present in both parent and child
        val sharedMethods = parentMethods.intersect(childMethods)
        sharedMethods.map { methodName =>
          ExtractedMethod(
            className = parentName,
            packageName = parentPkg,
            methodName = methodName,
            calls = List(MethodRef(childPkg, childName, methodName)),
          )
        }
      }
    }

    // Merge bridge entries with existing methods
    if (bridgeEntries.isEmpty) methods
    else {
      val bridgesByKey = bridgeEntries.groupBy(m => (m.packageName, m.className, m.methodName))
      val existingKeys = methods.map(m => (m.packageName, m.className, m.methodName)).toSet

      // Augment existing methods with bridge calls
      val augmented = methods.map { m =>
        val key = (m.packageName, m.className, m.methodName)
        bridgesByKey.get(key) match {
          case Some(bridges) =>
            val newCalls = bridges.flatMap(_.calls).filterNot(m.calls.contains)
            m.copy(calls = m.calls ++ newCalls)
          case None => m
        }
      }

      // Add bridge entries for methods not yet in the list (abstract methods with no body)
      val newEntries = bridgesByKey
        .collect { case (key, entries) if !existingKeys.contains(key) =>
          val allCalls = entries.flatMap(_.calls).distinct
          entries.head.copy(calls = allCalls)
        }

      augmented ++ newEntries
    }
  }

  private def resolveFieldTypes(cls: ClassSymbol): Map[String, (String, String)] =
    cls.declarations.collect {
      case ts: TermSymbol if !isUserMethod(ts) && !ts.name.toString.startsWith("<") =>
        TastyUtils.extractTypeRef(ts.declaredType) match {
          case Some(tr) =>
            val pkg = TastyUtils.typeRefPackage(tr)
            Some(ts.name.toString -> (pkg, tr.name.toString.stripSuffix("$")))
          case None => None
        }
    }.flatten.toMap

  private def extractCalls(ts: TermSymbol, fieldTypes: Map[String, (String, String)]): List[MethodRef] =
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

  private class MethodCallCollector(fieldTypes: Map[String, (String, String)]) extends TreeTraverser {
    val calls: ListBuffer[MethodRef] = ListBuffer.empty

    override def traverse(tree: Tree): Unit = {
      tree match {
        // field.method(args) — constructor params referenced as Ident
        case Apply(Select(Ident(fieldName), methodName), _) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case Apply(TypeApply(Select(Ident(fieldName), methodName), _), _) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        // this.field.method(args) — class body vals referenced via This
        case Apply(Select(Select(_: This, fieldName), methodName), _) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case Apply(TypeApply(Select(Select(_: This, fieldName), methodName), _), _) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case _ =>
      }
      super.traverse(tree)
    }

    private def addIfKnown(fieldName: String, methodName: String): Unit =
      fieldTypes.get(fieldName).foreach { case (pkg, className) =>
        calls += MethodRef(pkg, className, methodName)
      }
  }
}
