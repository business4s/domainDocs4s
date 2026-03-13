package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Traversers.*
import tastyquery.Trees.*
import tastyquery.Types.*

import scala.collection.mutable
import scala.util.control.NonFatal

// ============================================================================
// Param Value Index
//
// Indexes what argument was passed at each call site for each parameter
// position of every method — enabling backward resolution of string literals
// through arbitrary-depth call chains.
//
// Usage:
//   val index = ParamValueIndex.build(List("com.example"))
//   val names = index.resolve("com.example", "MyClass", "apply", 1)
//   // names: Set[String] — all string literals ever passed as arg 1 to apply
// ============================================================================

/** Indexes call-site argument sources for every method in the scanned packages.
  *
  * Enables backward resolution: given (pkg, cls, method, paramIndex),
  * return all string literals that were ever passed there — following
  * ForwardedParam and LocalLiteral chains recursively.
  *
  * Thread-safe (immutable after build).
  */
class ParamValueIndex private (
    entries: Map[(String, String, String, Int), List[ArgSource]],
) {
  /** Resolve all string literals ever passed as argument [paramIndex] to the given method.
    *
    * Follows ForwardedParam and LocalLiteral chains to arbitrary depth.
    * Cycle-safe: returns empty set if a cycle is detected.
    */
  def resolve(pkg: String, cls: String, method: String, paramIndex: Int): Set[String] =
    resolveRec(pkg, cls, method, paramIndex, visited = Set.empty)

  private def resolveRec(
      pkg: String, cls: String, method: String, paramIndex: Int,
      visited: Set[(String, String, String, Int)],
  ): Set[String] = {
    val key = (pkg, cls, method, paramIndex)
    if (visited.contains(key)) return Set.empty
    val newVisited = visited + key
    entries.getOrElse(key, Nil).flatMap {
      case ArgSource.Literal(v)      => Set(v)
      case ArgSource.LocalLiteral(v) => Set(v)
      case ArgSource.ForwardedParam(fp, fc, fm, fi) =>
        resolveRec(fp, fc, fm, fi, newVisited)
      case ArgSource.CallResult(_, _) => Set.empty  // Phase 4
      case ArgSource.Unresolvable     => Set.empty
    }.toSet
  }
}

object ParamValueIndex {

  /** Build the index by scanning all TASTy method bodies in the given packages. */
  def build(packages: List[String])(using ctx: Context): ParamValueIndex =
    new Builder().build(packages)

  // Builder holds all TASTy traversal logic in one place so ctx is available throughout.
  private class Builder(using ctx: Context) {

    def build(packages: List[String]): ParamValueIndex = {
      val entries = mutable.Map.empty[(String, String, String, Int), mutable.ListBuffer[ArgSource]]

      def addEntry(key: (String, String, String, Int), src: ArgSource): Unit =
        entries.getOrElseUpdate(key, mutable.ListBuffer.empty) += src

      for (packageName <- packages) {
        try {
          val pkg = ctx.findPackage(packageName)
          val classes =
            TastyUtils.userClassesRecursive(pkg) ++
            TastyUtils.moduleClassesRecursive(pkg) ++
            TastyUtils.nestedClassesInModules(pkg) ++
            TastyUtils.nestedModulesInModules(pkg)

          for ((ownerPkg, cls) <- classes) {
            val pkgName         = ownerPkg.fullName.toString
            val clsName         = cls.name.toString.stripSuffix("$")
            val fieldTypes      = resolveFieldTypes(cls)
            val classStringLits = resolveClassStringLiterals(cls)

            for (ts <- cls.declarations.collect { case ts: TermSymbol => ts }) {
              if (!ts.isSynthetic) {
                ts.tree match {
                  case Some(defDef: DefDef) =>
                    val methodName = TastyUtils.simpleName(ts.name)
                    val params     = buildParamList(defDef)
                    defDef.rhs.foreach { rhs =>
                      new ArgSourceCollector(
                        callerPkg       = pkgName,
                        callerCls       = clsName,
                        callerMethod    = methodName,
                        paramNames      = params,
                        fieldTypes      = fieldTypes,
                        classStringLits = classStringLits,
                        addEntry        = addEntry,
                      ).traverse(rhs)
                    }
                  // Class-level val initializers (e.g. val repo = Factory("name")) are not
                  // inside any DefDef body — process their rhs directly as <init> context.
                  case Some(valDef: ValDef) =>
                    valDef.rhs.foreach { rhs =>
                      new ArgSourceCollector(
                        callerPkg       = pkgName,
                        callerCls       = clsName,
                        callerMethod    = "<init>",
                        paramNames      = IndexedSeq.empty,
                        fieldTypes      = fieldTypes,
                        classStringLits = classStringLits,
                        addEntry        = addEntry,
                      ).traverse(rhs)
                    }
                  case _ =>
                }
              }
            }
          }
        } catch { case NonFatal(_) => }
      }

      new ParamValueIndex(entries.view.mapValues(_.toList).toMap)
    }

    // ── Class-level field introspection ───────────────────────────────────────

    private def resolveFieldTypes(cls: ClassSymbol): Map[String, (String, String)] =
      cls.declarations.collect {
        case ts: TermSymbol if !ts.name.toString.startsWith("<") =>
          try {
            TastyUtils.extractTypeRef(ts.declaredType).flatMap { tr =>
              val pkg  = TastyUtils.typeRefPackage(tr)
              val name = tr.name.toString.stripSuffix("$")
              if (pkg.nonEmpty) Some(ts.name.toString -> (pkg, name)) else None
            }
          } catch { case NonFatal(_) => None }
      }.flatten.toMap

    /** Collect class-level val bindings whose RHS is a string literal. */
    private def resolveClassStringLiterals(cls: ClassSymbol): Map[String, String] =
      cls.declarations.collect {
        case ts: TermSymbol if !ts.name.toString.startsWith("<") =>
          ts.tree match {
            case Some(vd: ValDef) =>
              vd.rhs match {
                case Some(Literal(c)) if c.value.isInstanceOf[String] =>
                  Some(ts.name.toString -> c.value.asInstanceOf[String])
                case _ => None
              }
            case _ => None
          }
      }.flatten.toMap

    /** Build a flat list of parameter names in declaration order. */
    private def buildParamList(defDef: DefDef): IndexedSeq[String] =
      defDef.paramLists.flatMap {
        case Left(params) => params.map(p => TastyUtils.simpleName(p.name))
        case Right(_)     => Nil
      }.toIndexedSeq

    // ── Tree traverser ────────────────────────────────────────────────────────

    private class ArgSourceCollector(
        callerPkg:       String,
        callerCls:       String,
        callerMethod:    String,
        paramNames:      IndexedSeq[String],
        fieldTypes:      Map[String, (String, String)],
        classStringLits: Map[String, String],
        addEntry:        ((String, String, String, Int), ArgSource) => Unit,
    ) extends TreeTraverser {

      // Local val bindings accumulated during traversal
      private val localStringLits = mutable.Map.empty[String, String]           // name → literal
      private val localVarTypes   = mutable.Map.empty[String, (String, String)] // name → (pkg, cls)

      override def traverse(tree: Tree): Unit = tree match {
        case vd: ValDef =>
          trackValDef(vd)
          super.traverse(tree)

        case Apply(_, _) =>
          val (baseFun, allArgs) = flattenApply(tree)
          resolveCallee(baseFun).foreach { callee =>
            allArgs.zipWithIndex.foreach { case (arg, i) =>
              addEntry(
                (callee.packageName, callee.className, callee.methodName, i),
                classifyArg(arg),
              )
            }
          }
          super.traverse(tree)

        case _ =>
          super.traverse(tree)
      }

      // ── Arg classification ───────────────────────────────────────────────

      private def classifyArg(arg: Tree): ArgSource = arg match {
        case NamedArg(_, value) =>
          classifyArg(value)
        case Literal(c) if c.value.isInstanceOf[String] =>
          ArgSource.Literal(c.value.asInstanceOf[String])
        case Ident(name) =>
          val n        = TastyUtils.simpleName(name)
          val paramIdx = paramNames.indexOf(n)
          if (paramIdx >= 0)
            ArgSource.ForwardedParam(callerPkg, callerCls, callerMethod, paramIdx)
          else
            localStringLits.get(n)
              .orElse(classStringLits.get(n))
              .map(ArgSource.LocalLiteral.apply)
              .getOrElse(ArgSource.Unresolvable)
        case _ => ArgSource.Unresolvable
      }

      // ── ValDef tracking ──────────────────────────────────────────────────

      private def trackValDef(vd: ValDef): Unit = {
        vd.rhs.foreach {
          case Literal(c) if c.value.isInstanceOf[String] =>
            localStringLits(TastyUtils.simpleName(vd.name)) = c.value.asInstanceOf[String]
          case _ =>
        }
        try {
          TastyUtils.extractTypeRef(vd.symbol.declaredType).foreach { tr =>
            val pkg     = TastyUtils.typeRefPackage(tr)
            val clsName = tr.name.toString.stripSuffix("$")
            if (pkg.nonEmpty)
              localVarTypes(TastyUtils.simpleName(vd.name)) = (pkg, clsName)
          }
        } catch { case NonFatal(_) => }
      }

      // ── Callee resolution ────────────────────────────────────────────────

      /** Unwrap nested Apply/TypeApply to get (rootFun, allArgsInFlatOrder).
        * For method(a)(b, c), TASTy is Apply(Apply(method,[a]),[b,c]).
        * flattenApply yields (method, [a, b, c]).
        */
      private def flattenApply(tree: Tree, accArgs: List[Tree] = Nil): (Tree, List[Tree]) =
        tree match {
          case Apply(fun, args)  => flattenApply(fun, args ++ accArgs)
          case TypeApply(fun, _) => flattenApply(fun, accArgs)
          case other             => (other, accArgs)
        }

      private def resolveCallee(fun: Tree): Option[MethodRef] = fun match {
        case TypeApply(inner, _) => resolveCallee(inner)

        case Ident(_) =>
          resolveIdentCallee(fun.asInstanceOf[Ident])

        case Select(recv, methodName) =>
          val mn = TastyUtils.simpleName(methodName)
          recv match {
            case ident @ Ident(fieldName) =>
              val fn = TastyUtils.simpleName(fieldName)
              fieldTypes.get(fn).orElse(localVarTypes.get(fn)) match {
                case Some((pkg, cls)) => Some(MethodRef(pkg, cls, mn))
                case None             => resolveModuleCallee(ident, mn)
              }
            case _: This =>
              Some(MethodRef(callerPkg, callerCls, mn))
            case Select(_: This, fieldName) =>
              fieldTypes.get(TastyUtils.simpleName(fieldName))
                .map { case (pkg, cls) => MethodRef(pkg, cls, mn) }
            case _ => None
          }

        case _ => None
      }

      private def resolveIdentCallee(ident: Ident): Option[MethodRef] =
        try {
          ident.referenceType match {
            case tr: TermRef =>
              tr.prefix match {
                case _: ThisType =>
                  Some(MethodRef(callerPkg, callerCls, TastyUtils.simpleName(tr.name)))
                case prefixTr: TermRef =>
                  val methodName = TastyUtils.simpleName(tr.name)
                  val pkg        = TastyUtils.termRefPackage(prefixTr)
                  val cls        = prefixTr.name.toString.stripSuffix("$")
                  if (pkg.nonEmpty) Some(MethodRef(pkg, cls, methodName)) else None
                case pr: PackageRef =>
                  val cls = tr.name.toString.stripSuffix("$")
                  val pkg = try pr.symbol.fullName.toString catch { case NonFatal(_) => "" }
                  if (pkg.nonEmpty) Some(MethodRef(pkg, cls, "apply")) else None
                case _ => None
              }
            case _ => None
          }
        } catch { case NonFatal(_) => None }

      /** Resolve a module (singleton object) call: Ident("Obj").method(args). */
      private def resolveModuleCallee(ident: Ident, methodName: String): Option[MethodRef] =
        try {
          ident.referenceType match {
            case tr: TermRef =>
              val pkg = TastyUtils.termRefPackage(tr)
              val cls = tr.name.toString.stripSuffix("$")
              if (pkg.nonEmpty) Some(MethodRef(pkg, cls, methodName)) else None
            case _ => None
          }
        } catch { case NonFatal(_) => None }

    } // ArgSourceCollector

  } // Builder

}
