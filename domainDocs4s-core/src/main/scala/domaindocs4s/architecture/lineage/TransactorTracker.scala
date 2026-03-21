package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*
import tastyquery.Types.*

import scala.collection.mutable
import scala.util.control.NonFatal

// ============================================================================
// Transactor Tracker — automatic database attribution via TASTy analysis
//
// Determines which classes connect to which database by analyzing:
//   1. Constructor/field types (opaque types, type aliases)
//   2. Constructor argument types at call sites
//   3. Constructor argument variable names (fallback)
//   4. Call-graph propagation (for ConnectionIO classes)
//
// Input:  TransactorMapping (user config) + packages + call graph + integrations
// Output: Map[(packageName, className), DbSegments]
// ============================================================================

class TransactorTracker(mapping: TransactorMapping)(using ctx: Context) {

  /** Run the full transactor tracking analysis. Returns a mapping from (packageName, className) to database segments.
    *
    * @throws IllegalStateException
    *   if any `.name()` entry in the mapping was never matched (scan-time verification)
    */
  def scan(
      packages: List[String],
      callGraph: List[ExtractedMethod],
      integrations: List[DiscoveredIntegration],
      logger: LineageLogger = LineageLogger.noop,
  ): Map[(String, String), DbSegments] = logger.timed("Transactor tracking") {
    if (mapping.isEmpty) Map.empty
    else {

    val result = mutable.Map.empty[(String, String), DbSegments]
    val matchedNames = mutable.Set.empty[String]

    // Enumerate classes once per package, reused across passes
    val classesByPkg = packages.map { pkgName =>
      val pkg = ctx.findPackage(pkgName)
      val base = TastyUtils.userClassesRecursive(pkg) ++ TastyUtils.moduleClassesRecursive(pkg)
      val nested = TastyUtils.nestedClassesInModules(pkg) ++ TastyUtils.nestedModulesInModules(pkg)
      (base, nested)
    }

    // Pass 1: Direct field type matching
    // For classes whose constructor params have a registered transactor type
    val pass1Count = logger.timed("  Pass 1: Field type matching") {
      val before = result.size
      for ((base, _) <- classesByPkg; (ownerPkg, cls) <- base) {
        val clsPkg = ownerPkg.fullName.toString
        val clsName = cls.name.toString.stripSuffix("$")
        matchFieldTypes(cls, clsPkg, clsName, result)
      }
      result.size - before
    }
    logger.log(s"    matched $pass1Count classes")

    // Pass 2: Constructor/factory arg matching at call sites
    val pass2Count = logger.timed("  Pass 2: Constructor arg matching") {
      val before = result.size
      for ((base, nested) <- classesByPkg; (_, cls) <- base ++ nested) {
        walkClassForConstructorArgs(cls, result, matchedNames)
      }
      result.size - before
    }
    logger.log(s"    matched $pass2Count additional classes")

    // Pass 3: Call-graph propagation for ConnectionIO classes
    val pass3Count = if (callGraph.nonEmpty) {
      logger.timed("  Pass 3: Call-graph propagation") {
        val before = result.size
        propagateThroughCallGraph(callGraph, integrations, result)
        result.size - before
      }
    } else 0
    logger.log(s"    propagated to $pass3Count additional classes")

    // Validate: every .name() entry must have been matched at least once
    val unmatchedNames = mapping.byName.keySet -- matchedNames
    if (unmatchedNames.nonEmpty) {
      throw new IllegalStateException(
        s"TransactorMapping: the following .name() entries were never matched as constructor arguments: ${unmatchedNames.mkString(", ")}. " +
          "Check for typos or stale variable names.",
      )
    }

    logger.log(s"  Total: ${result.size} classes mapped to databases")
    result.toMap
    }
  }

  // ── Pass 1: Field type matching ──────────────────────────────────────────

  private def matchFieldTypes(
      cls: ClassSymbol,
      clsPkg: String,
      clsName: String,
      result: mutable.Map[(String, String), DbSegments],
  ): Unit = {
    // Check constructor params
    val ctorParams = cls.declarations.collectFirst {
      case ts: TermSymbol if ts.name.toString == "<init>" =>
        ts.tree.collect { case defDef: DefDef =>
          defDef.paramLists.collect { case Left(params) => params }.flatten
        }.getOrElse(Nil)
    }.getOrElse(Nil)

    for (param <- ctorParams) {
      try {
        val fqn = extractTypeFqn(param.symbol.declaredType)
        fqn.foreach { f =>
          mapping.byTypeFqn.get(f).foreach { segs =>
            result += (clsPkg, clsName) -> segs
          }
        }
      } catch { case NonFatal(_) => }
    }

    // Also check val fields (for captured/forwarded transactors)
    cls.declarations.foreach {
      case ts: TermSymbol if !ts.name.toString.startsWith("<") && !ts.tree.exists(_.isInstanceOf[DefDef]) =>
        try {
          val fqn = extractTypeFqn(ts.declaredType)
          fqn.foreach { f =>
            mapping.byTypeFqn.get(f).foreach { segs =>
              result += (clsPkg, clsName) -> segs
            }
          }
        } catch { case NonFatal(_) => }
      case _ =>
    }
  }

  // ── Pass 2: Constructor arg matching ─────────────────────────────────────

  private def walkClassForConstructorArgs(
      cls: ClassSymbol,
      result: mutable.Map[(String, String), DbSegments],
      matchedNames: mutable.Set[String],
  ): Unit = {
    val fieldTypes = resolveFieldTypes(cls)

    // Walk method bodies
    cls.declarations.foreach {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) && !ts.isSynthetic =>
        ts.tree.foreach {
          case defDef: DefDef =>
            val paramTypes = extractParamTypes(defDef)
            val combinedCtx = fieldTypes ++ paramTypes
            defDef.rhs.foreach { rhs =>
              val walker = new ConstructorArgWalker(combinedCtx, result, matchedNames)
              walker.traverse(rhs)
            }
          case _ =>
        }
      // Walk val bodies
      case ts: TermSymbol
          if !ts.isSynthetic && !ts.name.toString.startsWith("<") &&
            ts.tree.exists(_.isInstanceOf[ValDef]) && !ts.tree.exists(_.isInstanceOf[DefDef]) =>
        ts.tree.foreach {
          case valDef: ValDef =>
            valDef.rhs.foreach { rhs =>
              val walker = new ConstructorArgWalker(fieldTypes, result, matchedNames)
              walker.traverse(rhs)
            }
          case _ =>
        }
      case _ =>
    }
  }

  /** Walks a tree looking for constructor calls and factory method calls, inspecting arguments for transactor sources. */
  private class ConstructorArgWalker(
      typeCtx: Map[String, (String, String)],
      result: mutable.Map[(String, String), DbSegments],
      matchedNames: mutable.Set[String],
  ) extends TreeTraverser {

    // Local variable types accumulated during traversal
    private val localTypes = mutable.Map.from(typeCtx)

    override def traverse(tree: Tree): Unit = {
      tree match {
        // Track local variable types
        case vd: ValDef =>
          try {
            TastyUtils.extractTypeRef(vd.symbol.declaredType).foreach { tr =>
              val pkg = TastyUtils.typeRefPackage(tr)
              val cls = tr.name.toString.stripSuffix("$")
              if (pkg.nonEmpty) localTypes(TastyUtils.simpleName(vd.name)) = (pkg, cls)
            }
            // Also record the type FQN directly (for opaque type / alias matching)
            extractTypeFqn(vd.symbol.declaredType).foreach { fqn =>
              localTypes(TastyUtils.simpleName(vd.name)) = fqnToPkgClass(fqn)
            }
          } catch { case NonFatal(_) => }

        // Constructor call: new SomeClass(args)
        case Apply(Select(New(typeTree), _), args) =>
          handleConstructorArgs(typeTree, args)
        case Apply(TypeApply(Select(New(typeTree), _), _), args) =>
          handleConstructorArgs(typeTree, args)

        // Companion apply call: SomeClass(args) or SomeClass.apply(args)
        case Apply(ident: Ident, args) =>
          handleCompanionCall(ident, args)
        case Apply(TypeApply(ident: Ident, _), args) =>
          handleCompanionCall(ident, args)

        // Factory method call: Companion.method(args) — e.g., Repo.forIO(xa)
        case Apply(Select(receiver, _), args) if !receiver.isInstanceOf[New] =>
          handleFactoryCall(receiver, args)
        case Apply(TypeApply(Select(receiver, _), _), args) if !receiver.isInstanceOf[New] =>
          handleFactoryCall(receiver, args)

        case _ =>
      }
      super.traverse(tree)
    }

    private def handleConstructorArgs(typeTree: TypeTree, args: List[Tree]): Unit =
      try {
        val tpe = typeTree.toType
        TastyUtils.extractTypeRef(tpe).foreach { tr =>
          val pkg = TastyUtils.typeRefPackage(tr)
          val cls = tr.name.toString.stripSuffix("$")
          if (pkg.nonEmpty) tryMatchArgs(pkg, cls, args)
        }
      } catch { case NonFatal(_) => }

    private def handleCompanionCall(ident: Ident, args: List[Tree]): Unit =
      try {
        ident.referenceType match {
          case tr: TermRef =>
            tr.prefix match {
              case pr: PackageRef =>
                val pkg = pr.symbol.fullName.toString
                val cls = tr.name.toString.stripSuffix("$")
                if (pkg.nonEmpty) tryMatchArgs(pkg, cls, args)
              case _ =>
            }
          case _ =>
        }
      } catch { case NonFatal(_) => }

    /** Handle factory method calls like Repo.forIO(xa). The target class is the companion's class (strip $). */
    private def handleFactoryCall(receiver: Tree, args: List[Tree]): Unit =
      try {
        val (pkg, companionName) = receiver match {
          case Ident(name)           =>
            val n = TastyUtils.simpleName(name)
            localTypes.get(n) match {
              case Some((p, c)) => (p, c)
              case None         =>
                // Try TermRef resolution for module references
                receiver match {
                  case trt: TermReferenceTree =>
                    trt.referenceType match {
                      case tr: TermRef =>
                        val p = TastyUtils.termRefPackage(tr)
                        val c = tr.name.toString.stripSuffix("$")
                        (p, c)
                      case _           => ("", "")
                    }
                  case _                      => ("", "")
                }
            }
          case Select(_: This, name) =>
            localTypes.get(TastyUtils.simpleName(name)).getOrElse(("", ""))
          case _                     => ("", "")
        }
        if (pkg.nonEmpty) {
          // For Companion.method(xa), attribute to the class name (strip $ from companion)
          val className = companionName.stripSuffix("$")
          tryMatchArgs(pkg, className, args)
        }
      } catch { case NonFatal(_) => }

    /** Check if any arg matches a registered transactor source (by type or by name). */
    private def tryMatchArgs(targetPkg: String, targetClass: String, args: List[Tree]): Unit = {
      for (arg <- args) {
        // Try type-based matching first
        val typeMatch = argTypeFqn(arg).flatMap(mapping.byTypeFqn.get)

        // Try name-based matching
        val nameMatch = if (typeMatch.isEmpty) {
          val sourceName = extractSourceName(arg)
          sourceName.flatMap { name =>
            matchByName(name).map { case (matchedKey, segs) =>
              matchedNames += matchedKey
              segs
            }
          }
        } else None

        (typeMatch orElse nameMatch).foreach { segs =>
          result += (targetPkg, targetClass) -> segs
        }
      }
    }

    /** Extract the type FQN of an argument (for type-based matching). */
    private def argTypeFqn(arg: Tree): Option[String] = arg match {
      case Ident(name)           =>
        val n = TastyUtils.simpleName(name)
        localTypes.get(n).map { case (pkg, cls) => s"$pkg.$cls" }.flatMap { fqn =>
          // Check if ANY prefix of the FQN matches a registered type
          if (mapping.byTypeFqn.contains(fqn)) Some(fqn) else None
        }.orElse {
          // Try declared type from the symbol
          try {
            arg match {
              case trt: TermReferenceTree =>
                trt.referenceType match {
                  case tr: TermRef => extractTypeFqn(tr).filter(mapping.byTypeFqn.contains)
                  case _           => None
                }
              case _                      => None
            }
          } catch { case NonFatal(_) => None }
        }
      case Select(qual, _)       =>
        // For x.field, check the overall expression type
        try {
          arg match {
            case tt: TermTree =>
              tt.tpe match {
                case t: Type => extractTypeFqn(t).filter(mapping.byTypeFqn.contains)
                case _       => None
              }
            case _            => None
          }
        } catch { case NonFatal(_) => None }
      case Typed(expr, typeTree) =>
        // Type ascription: (expr: SomeType) — check the ascribed type
        try {
          extractTypeFqn(typeTree.toType).filter(mapping.byTypeFqn.contains)
        } catch { case NonFatal(_) => argTypeFqn(expr) }
      case _                     => None
    }

    /** Extract source name from a constructor argument tree. */
    private def extractSourceName(arg: Tree): Option[String] = arg match {
      case Ident(name)                    => Some(TastyUtils.simpleName(name))
      case Select(Ident(qual), field)     => Some(s"${TastyUtils.simpleName(qual)}.${TastyUtils.simpleName(field)}")
      case Select(_: This, field)         => Some(s"this.${TastyUtils.simpleName(field)}")
      case Select(inner @ Select(_, _), f2) =>
        extractSourceName(inner).map(base => s"$base.${TastyUtils.simpleName(f2)}")
      case Typed(expr, _)                 => extractSourceName(expr)
      case _                              => None
    }

    /** Match a source name against the byName mapping (exact or prefix). */
    private def matchByName(sourceName: String): Option[(String, DbSegments)] =
      // Exact match first
      mapping.byName.get(sourceName).map(sourceName -> _).orElse {
        // Prefix match: "transactors" matches "transactors.writer"
        mapping.byName.find { case (key, _) =>
          sourceName.startsWith(key + ".")
        }
      }
  }

  // ── Pass 3: Call-graph propagation ───────────────────────────────────────

  /** For classes with doobie integrations but no direct transactor mapping, try to inherit from callers. */
  private def propagateThroughCallGraph(
      callGraph: List[ExtractedMethod],
      integrations: List[DiscoveredIntegration],
      result: mutable.Map[(String, String), DbSegments],
  ): Unit = {
    val dbIntegrationClasses = integrations
      .filter(_.resourceType == ResourceType.Database)
      .map(i => (i.method.packageName, i.method.className))
      .toSet

    val unmappedDbClasses = dbIntegrationClasses -- result.keySet
    if (unmappedDbClasses.isEmpty) return

    // Build caller → callee adjacency
    val callerOf = mutable.Map.empty[(String, String), mutable.Set[(String, String)]]
    for (m <- callGraph; call <- m.calls) {
      val calleeKey = (call.packageName, call.className)
      callerOf.getOrElseUpdate(calleeKey, mutable.Set.empty) += ((m.packageName, m.className))
    }

    for (unmapped <- unmappedDbClasses) {
      val callers = callerOf.getOrElse(unmapped, Set.empty)
      val callerSegments = callers.flatMap(result.get).toList.distinct
      if (callerSegments.size == 1) {
        result += unmapped -> callerSegments.head
      }
    }
  }

  // ── Utilities ────────────────────────────────────────────────────────────

  /** Extract a type's FQN via the symbol's owner chain.
    *
    * This handles types inside objects (e.g., opaque types in `object Transactors`) correctly, producing FQNs like
    * `com.swissborg.service.db.Transactors.RedshiftXa` that match what `TypeFqnMacro.fqn[T]` produces.
    */
  private def extractTypeFqn(tpe: TypeOrMethodic): Option[String] =
    try {
      TastyUtils.extractTypeRef(tpe).flatMap { tr =>
        tr.optSymbol.map(symbolFqn)
      }.orElse {
        tpe match {
          case tr: TermRef =>
            try { Some(symbolFqn(tr.symbol)) } catch { case NonFatal(_) => None }
          case _           => None
        }
      }
    } catch { case NonFatal(_) => None }

  /** Build an FQN from a symbol's owner chain, matching `TypeFqnMacro.fqn` format: `owner.fullName + "." + name`. */
  private def symbolFqn(sym: Symbol): String = {
    val parts = mutable.ListBuffer(sym.name.toString.stripSuffix("$"))
    var current: Symbol = sym.owner
    while (current != null && !current.isInstanceOf[PackageSymbol]) {
      parts.prepend(current.name.toString.stripSuffix("$"))
      current = current.owner
    }
    current match {
      case pkg: PackageSymbol =>
        val pkgName = pkg.fullName.toString
        if (pkgName.nonEmpty) s"$pkgName.${parts.mkString(".")}" else parts.mkString(".")
      case _                  => parts.mkString(".")
    }
  }

  private def fqnToPkgClass(fqn: String): (String, String) = {
    val lastDot = fqn.lastIndexOf('.')
    if (lastDot >= 0) (fqn.substring(0, lastDot), fqn.substring(lastDot + 1))
    else ("", fqn)
  }

  private def resolveFieldTypes(cls: ClassSymbol): Map[String, (String, String)] =
    cls.declarations
      .collect {
        case ts: TermSymbol if !ts.name.toString.startsWith("<") && !ts.tree.exists(_.isInstanceOf[DefDef]) =>
          try {
            TastyUtils.extractTypeRef(ts.declaredType).flatMap { tr =>
              val pkg = TastyUtils.typeRefPackage(tr)
              if (pkg.nonEmpty) Some(ts.name.toString -> (pkg, tr.name.toString.stripSuffix("$")))
              else None
            }
          } catch { case NonFatal(_) => None }
      }
      .flatten
      .toMap

  private def extractParamTypes(defDef: DefDef): Map[String, (String, String)] =
    defDef.paramLists.flatMap {
      case Left(params) =>
        params.flatMap { p =>
          try {
            TastyUtils.extractTypeRef(p.symbol.declaredType).flatMap { tr =>
              val pkg = TastyUtils.typeRefPackage(tr)
              val cls = tr.name.toString.stripSuffix("$")
              if (pkg.nonEmpty) Some(TastyUtils.simpleName(p.name) -> (pkg, cls)) else None
            }
          } catch { case NonFatal(_) => None }
        }
      case Right(_)     => Nil
    }.toMap
}
