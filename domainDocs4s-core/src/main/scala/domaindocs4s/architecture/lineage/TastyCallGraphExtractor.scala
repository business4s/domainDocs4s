package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*
import tastyquery.Types.*

import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

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
    val userClasses = TastyUtils.userClassesRecursive(pkg)
    val userClassNames = userClasses.map { case (p, c) => (p.fullName.toString, c.name.toString.stripSuffix("$")) }.toSet
    // Include module classes (singleton objects) that don't have a corresponding user class,
    // so standalone objects like DailyBalanceChangeProjection are included.
    val standaloneModules = TastyUtils.moduleClassesRecursive(pkg).filterNot { case (p, c) =>
      userClassNames.contains((p.fullName.toString, c.name.toString.stripSuffix("$")))
    }
    // Include classes nested inside companion objects (e.g., object Foo { case class Impl(...) })
    // Only include nested classes that have at least one user method — filters out ADT case classes,
    // protocol messages, etc. that would bloat the scan without contributing to the call graph.
    val hasUserMethods: ((PackageSymbol, ClassSymbol)) => Boolean = { case (_, cls) =>
      cls.declarations.exists {
        case ts: TermSymbol => isUserMethod(ts)
        case _              => false
      }
    }
    val nestedClasses = TastyUtils.nestedClassesInModules(pkg).filter(hasUserMethods)
    // Include module classes (objects) nested inside other module classes
    // (e.g., object OuterJob { object Helpers { def process(...) } })
    val nestedModules = TastyUtils.nestedModulesInModules(pkg).filter(hasUserMethods)
    val classesWithPkg = userClasses ++ standaloneModules ++ nestedClasses ++ nestedModules

    // Build class→userMethods index for constructor call resolution.
    // When we see `new SomeClass(...)`, we link to all user methods of SomeClass.
    val classMethodsIndex: Map[(String, String), List[String]] = classesWithPkg.map { case (ownerPkg, cls) =>
      val cn = cls.name.toString.stripSuffix("$")
      val pn = ownerPkg.fullName.toString
      val methodNames = cls.declarations.collect {
        case ts: TermSymbol if isUserMethod(ts) => ts.name.toString
      }
      (pn, cn) -> methodNames
    }.toMap

    val methods = classesWithPkg.flatMap { case (ownerPkg, cls) =>
      val className = cls.name.toString.stripSuffix("$")
      val pkgName = ownerPkg.fullName.toString
      val fieldTypes = resolveFieldTypes(cls)

      val selfClass = Some((pkgName, className))

      // Process DefDef methods (existing)
      val defMethods = cls.declarations.collect {
        case ts: TermSymbol if isUserMethod(ts) =>
          val calls = extractCalls(ts, fieldTypes, classMethodsIndex, selfClass)
          ExtractedMethod(className, pkgName, ts.name.toString, calls)
      }

      // Process ValDef bodies — picks up constructor calls (new SomeClass(...))
      // and field.method() patterns inside val initializers.
      // Vals remain in fieldTypes for other methods to reference.
      val valMethods = cls.declarations.collect {
        case ts: TermSymbol if isValWithBody(ts) =>
          ts.tree.toList.flatMap {
            case valDef: ValDef =>
              valDef.rhs.toList.flatMap { rhs =>
                val collector = new MethodCallCollector(fieldTypes, classMethodsIndex, selfClass)
                collector.traverse(rhs)
                val calls = collector.calls.distinct.toList
                if (calls.nonEmpty) List(ExtractedMethod(className, pkgName, ts.name.toString, calls))
                else Nil
              }
            case _ => Nil
          }
      }.flatten

      defMethods ++ valMethods
    }

    addInheritanceEdges(classesWithPkg, methods, classMethodsIndex)
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
      classMethodsIndex: Map[(String, String), List[String]],
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
          } catch { case NonFatal(_) => Nil }
        }
        .groupMap(_._1)(_._2)

    // Index: (pkg, className) → Set[methodName] from existing methods with bodies
    val methodsByClass: Map[(String, String), Set[String]] =
      methods.groupBy(m => (m.packageName, m.className)).map { case (k, ms) =>
        k -> ms.map(_.methodName).toSet
      }

    // For each parent class that has children, create bridge edges.
    // Use classMethodsIndex (includes abstract methods) for parent methods so that
    // trait abstract methods also get bridges to concrete implementations.
    val bridgeEntries: List[ExtractedMethod] = classesWithPkg.flatMap { case (ownerPkg, cls) =>
      val parentPkg = ownerPkg.fullName.toString
      val parentName = cls.name.toString.stripSuffix("$")
      val parentFqn = s"$parentPkg.$parentName"
      val parentMethods = classMethodsIndex.getOrElse((parentPkg, parentName), Nil).toSet

      parentToChildren.getOrElse(parentFqn, Nil).flatMap { case (childPkg, childName) =>
        val childMethods = methodsByClass.getOrElse((childPkg, childName), Set.empty)
        // Methods declared in parent and implemented in child
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
            val existingCalls = m.calls.toSet
            val newCalls = bridges.flatMap(_.calls).filterNot(existingCalls.contains)
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

  private def extractCalls(
      ts: TermSymbol,
      fieldTypes: Map[String, (String, String)],
      classMethodsIndex: Map[(String, String), List[String]],
      selfClass: Option[(String, String)] = None,
  ): List[MethodRef] =
    ts.tree match {
      case Some(defDef: DefDef) =>
        val paramTypes = extractParamTypes(defDef)
        defDef.rhs.toList.flatMap { rhs =>
          val collector = new MethodCallCollector(fieldTypes, classMethodsIndex, selfClass, paramTypes)
          collector.traverse(rhs)
          collector.calls.distinct
        }
      case _ => Nil
    }

  /** Extract name→(package, className) mappings from method parameters. */
  private def extractParamTypes(defDef: DefDef): Map[String, (String, String)] =
    defDef.paramLists.flatMap {
      case Left(params) => params.flatMap { p =>
        try {
          TastyUtils.extractTypeRef(p.symbol.declaredType).flatMap { tr =>
            val pkg = TastyUtils.typeRefPackage(tr)
            val cls = tr.name.toString.stripSuffix("$")
            if (pkg.nonEmpty) Some(TastyUtils.simpleName(p.name) -> (pkg, cls)) else None
          }
        } catch { case NonFatal(_) => None }
      }
      case Right(_) => Nil
    }.toMap

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

  /** Check if a TermSymbol is a val with a non-trivial body (not a constructor param accessor). */
  private def isValWithBody(ts: TermSymbol): Boolean = {
    val name = ts.name.toString
    !name.startsWith("<") &&
    !name.startsWith("_") &&
    !ts.isSynthetic &&
    ts.tree.exists(_.isInstanceOf[ValDef]) &&
    !ts.tree.exists(_.isInstanceOf[DefDef]) && // not a method
    ts.tree.exists {
      case vd: ValDef => vd.rhs.isDefined
      case _          => false
    }
  }

  private class MethodCallCollector(
      fieldTypes: Map[String, (String, String)],
      classMethodsIndex: Map[(String, String), List[String]],
      selfClass: Option[(String, String)] = None, // (packageName, className) for this.method() calls
      paramTypes: Map[String, (String, String)] = Map.empty, // method parameter types
  ) extends TreeTraverser {
    val calls: ListBuffer[MethodRef] = ListBuffer.empty
    // Track local variable and parameter types for resolving localVar.method() calls.
    // Seeded from method parameters; populated from ValDef nodes in Block stats.
    private val localVarTypes: scala.collection.mutable.Map[String, (String, String)] = scala.collection.mutable.Map.from(paramTypes)

    override def traverse(tree: Tree): Unit = {
      tree match {
        // new SomeClass(args) — constructor call. Link to all user methods of the constructed class.
        case Apply(Select(New(typeTree), _), _) =>
          addConstructorCalls(typeTree)
        case Apply(TypeApply(Select(New(typeTree), _), _), _) =>
          addConstructorCalls(typeTree)
        // Track local variable types from ValDef for later localVar.method() resolution.
        // When `val svc = Foo(args)` appears in a block, we record svc's type so that
        // `svc.bar()` resolves to MethodRef(pkg, "Foo", "bar") — only for methods actually called.
        case vd: ValDef =>
          trackLocalVarType(vd)
        // field.method(args) — constructor params and local vars referenced as Ident
        case Apply(Select(Ident(fieldName), methodName), _) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case Apply(TypeApply(Select(Ident(fieldName), methodName), _), _) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        // this.field.method(args) — class body vals referenced via This
        case Apply(Select(Select(_: This, fieldName), methodName), _) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case Apply(TypeApply(Select(Select(_: This, fieldName), methodName), _), _) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        // this.method(args) — direct intra-class method call
        case Apply(Select(_: This, methodName), _) =>
          addSelfCall(TastyUtils.simpleName(methodName))
        case Apply(TypeApply(Select(_: This, methodName), _), _) =>
          addSelfCall(TastyUtils.simpleName(methodName))
        // Imported function call: importedFunc(args) where the function was imported from another object.
        // The Ident's TermRef prefix chain contains the owning class.
        case Apply(ident: Ident, _) =>
          addImportedCall(ident)
        case Apply(TypeApply(ident: Ident, _), _) =>
          addImportedCall(ident)
        // No-arg method/property access: field.method (without Apply wrapper).
        // Handles patterns like `journalReader.currentOperatorTransactions` which produce
        // Select(Ident(fieldName), methodName) without an Apply wrapper.
        case Select(Ident(fieldName), methodName) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case Select(Select(_: This, fieldName), methodName) =>
          addIfKnown(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case _ =>
      }
      super.traverse(tree)
    }

    private def trackLocalVarType(vd: ValDef): Unit =
      try {
        TastyUtils.extractTypeRef(vd.symbol.declaredType).foreach { tr =>
          val pkg = TastyUtils.typeRefPackage(tr)
          val clsName = tr.name.toString.stripSuffix("$")
          if (pkg.nonEmpty)
            localVarTypes(TastyUtils.simpleName(vd.name)) = (pkg, clsName)
        }
      } catch { case NonFatal(_) => }

    private def addIfKnown(fieldName: String, methodName: String): Unit =
      fieldTypes.get(fieldName).orElse(localVarTypes.get(fieldName)).foreach { case (pkg, className) =>
        calls += MethodRef(pkg, className, methodName)
      }

    private def addSelfCall(methodName: String): Unit =
      selfClass.foreach { case (pkg, className) =>
        calls += MethodRef(pkg, className, methodName)
      }

    /** Resolve an imported function call (Apply(Ident(name), args)) to a MethodRef.
      * Walks the Ident's TermRef prefix chain to find the owning class.
      * Skips ThisType prefixes (local methods handled by addSelfCall).
      */
    private def addImportedCall(ident: Ident): Unit =
      try {
        ident.referenceType match {
          case tr: TermRef =>
            tr.prefix match {
              case _: ThisType => () // local method, handled by addSelfCall
              case prefixTr: TermRef =>
                val methodName = TastyUtils.simpleName(tr.name)
                val pkg = TastyUtils.termRefPackage(prefixTr)
                val className = prefixTr.name.toString.stripSuffix("$")
                if (pkg.nonEmpty && classMethodsIndex.contains((pkg, className))) {
                  calls += MethodRef(pkg, className, methodName)
                }
              case _ =>
            }
          case _ =>
        }
      } catch { case NonFatal(_) => }

    private def addConstructorCalls(typeTree: TypeTree): Unit =
      try {
        val tpe = typeTree.toType
        TastyUtils.extractTypeRef(tpe).foreach { tr =>
          val pkg = TastyUtils.typeRefPackage(tr)
          val clsName = tr.name.toString.stripSuffix("$")
          classMethodsIndex.get((pkg, clsName)).foreach { methodNames =>
            methodNames.foreach(m => calls += MethodRef(pkg, clsName, m))
          }
        }
      } catch { case NonFatal(_) => }
  }
}
