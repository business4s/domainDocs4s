package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Types.*

import scala.collection.mutable.ListBuffer

// ============================================================================
// SymbolUsageFinder — generic TASTy tree walker for finding symbol usages.
//
// Layer 1 of the two-layer scanner architecture:
//   Layer 1: SymbolUsageFinder (this file) — walks TASTy trees, finds usages by FQN
//   Layer 2: Scanners — declare what to search for, interpret structured results
//
// All matching is FQN-based via TypeMatcher. The walker handles all TASTy tree
// patterns uniformly, including anonymous/nested classes.
// ============================================================================

// ── What to search for ───────────────────────────────────────────────────────

sealed trait SymbolSearch
object SymbolSearch {

  /** Find method calls where receiver/owner type matches. Covers: instance calls (field.method), companion calls (Companion.method), constructor
    * calls (new Type), no-arg calls (field.property).
    */
  case class MethodCall(ownerType: TypeMatcher) extends SymbolSearch

  /** Find classes that extend/implement a matching type. */
  case class ClassInheritance(parentType: TypeMatcher) extends SymbolSearch
}

// ── Where a usage was found ──────────────────────────────────────────────────

sealed trait NestingNode
object NestingNode {
  case class Package(fqn: String) extends NestingNode
  case class ClassOrObject(
      name: String,
      isModule: Boolean,
      tree: Option[ClassDef],
  ) extends NestingNode
  case class Method(
      name: String,
      tree: Option[DefDef],
  ) extends NestingNode
}

case class NestingPath(nodes: List[NestingNode]) {

  /** Outermost non-anonymous class/object + innermost method -> MethodRef. Anonymous class methods are attributed to the enclosing module.
    */
  def toMethodRef: MethodRef = {
    // > is toMethodRef correct? I would expect some support for inner/nested classes and concat of them?
    val pkg    = nodes.collectFirst { case NestingNode.Package(fqn) => fqn }.getOrElse("")
    // Find outermost non-anonymous class/object
    val cls    = nodes
      .collect { case c: NestingNode.ClassOrObject => c }
      .find(c => !c.name.startsWith("$anon") && !c.name.contains("$anon"))
      .orElse(nodes.collectFirst { case c: NestingNode.ClassOrObject => c })
      .map(_.name)
      .getOrElse("")
    val method = nodes.reverse.collectFirst { case NestingNode.Method(name, _) => name }.getOrElse("<class>")
    MethodRef(pkg, cls, method)
  }
}

// ── Extracted argument literals ──────────────────────────────────────────────

sealed trait LiteralValue
object LiteralValue {
  case class StringLit(value: String) extends LiteralValue
  case class IntLit(value: Int)       extends LiteralValue
  case class LongLit(value: Long)     extends LiteralValue
  case class DoubleLit(value: Double) extends LiteralValue
  case class BoolLit(value: Boolean)  extends LiteralValue
}

// ── What was found ───────────────────────────────────────────────────────────

sealed trait FoundUsage {
  def search: SymbolSearch
  def path: NestingPath
  def tree: Tree
}

object FoundUsage {

  /** A method/constructor call on a matching type was found. */
  case class MethodCallResult(
      search: SymbolSearch,
      path: NestingPath,
      tree: Tree,
      ownerFqn: String,
      ownerSimpleName: String,
      methodName: String,
      args: Map[String, Option[LiteralValue]],
      receiverTree: Tree,
  ) extends FoundUsage {

    /** Simple name of the receiver expression, for use in evidence strings. */
    lazy val receiverName: String = receiverTree match {
      case Ident(name)           => TastyUtils.simpleName(name)
      case Select(_: This, name) => TastyUtils.simpleName(name)
      case Select(_, name)       => TastyUtils.simpleName(name)
      case _                     => "?"
    }
  }

  /** A class inheriting from a matching type was found. */
  case class InheritanceResult(
      search: SymbolSearch,
      path: NestingPath,
      tree: Tree,
      parentFqn: String,
      parentSimpleName: String,
      inheritedMethods: List[String],
  ) extends FoundUsage
}

// ── SymbolUsageFinder ────────────────────────────────────────────────────────

class SymbolUsageFinder(searches: Seq[SymbolSearch])(using ctx: Context) {

  private val methodCallSearches  = searches.collect { case s: SymbolSearch.MethodCall => s }
  private val inheritanceSearches = searches.collect { case s: SymbolSearch.ClassInheritance => s }

  /** Find all usages across the given packages. */
  def findAll(packages: List[String]): List[FoundUsage] =
    packages.flatMap(findInPackage)

  private def findInPackage(packageName: String): List[FoundUsage] = {
    val pkg           = ctx.findPackage(packageName)
    val userWithPkg   = TastyUtils.userClassesRecursive(pkg)
    val moduleWithPkg = TastyUtils.moduleClassesRecursive(pkg)
    val allWithPkg    = userWithPkg ++ moduleWithPkg
    allWithPkg.flatMap { case (ownerPkg, cls) =>
      val pkgName   = ownerPkg.fullName.toString
      val className = cls.name.toString.stripSuffix("$")
      val isModule  = cls.name.toString.endsWith("$")
      val basePath  = List(NestingNode.Package(pkgName))

      val classTree = cls.tree.collect { case cd: ClassDef => cd }
      val classNode = NestingNode.ClassOrObject(className, isModule, classTree)
      val path      = basePath :+ classNode

      val results = ListBuffer.empty[FoundUsage]

      // 1. Check ClassInheritance searches
      if (inheritanceSearches.nonEmpty) {
        results ++= checkInheritance(cls, path, classTree)
      }

      // 2. Walk method bodies (with field pre-scan for receiver type resolution)
      if (methodCallSearches.nonEmpty) {
        val fieldCtx = buildFieldContext(cls)
        walkClassMethods(cls, path, fieldCtx, results)
      }

      results.toList
    }
  }

  // ── Inheritance checking ─────────────────────────────────────────────────

  private def checkInheritance(
      cls: ClassSymbol,
      path: List[NestingNode],
      classTree: Option[ClassDef],
  ): List[FoundUsage] = {
    val parents =
      try cls.parents
      catch { case _: Exception => Nil }
    parents.flatMap { parentType =>
      inheritanceSearches.flatMap { search =>
        if (TypeMatcherResolver.matches(search.parentType, parentType)) {
          val parentFqn        = TastyUtils.extractFqn(parentType).getOrElse("")
          val parentSimpleName = TastyUtils.extractTypeName(parentType).getOrElse("")
          val inheritedMethods = resolveParentMethods(parentType)
          val nestingPath      = NestingPath(path)
          val tree             = classTree.getOrElse(null: ClassDef) // ClassDef tree
          if (tree != null) {
            List(
              FoundUsage.InheritanceResult(
                search = search,
                path = nestingPath,
                tree = tree,
                parentFqn = parentFqn,
                parentSimpleName = parentSimpleName,
                inheritedMethods = inheritedMethods,
              ),
            )
          } else Nil
        } else Nil
      }
    }
  }

  private def resolveParentMethods(parentType: TypeOrMethodic): List[String] =
    TastyUtils.resolveSymbol(parentType) match {
      case Some(cs: ClassSymbol) =>
        cs.declarations.collect {
          case ts: TermSymbol if isUserDeclaration(ts) => ts.name.toString
        }
      case _                     => Nil
    }

  private def isUserDeclaration(ts: TermSymbol): Boolean = {
    val name = ts.name.toString
    !name.startsWith("<") && !name.startsWith("$")
  }

  // ── Field context building ───────────────────────────────────────────────

  /** Map field names to their declared types for receiver resolution. */
  private def buildFieldContext(cls: ClassSymbol): Map[String, TypeOrMethodic] =
    cls.declarations.collect {
      case ts: TermSymbol if !ts.name.toString.startsWith("<") && !ts.tree.exists(_.isInstanceOf[DefDef]) =>
        ts.name.toString -> ts.declaredType
    }.toMap

  // ── Method body walking ──────────────────────────────────────────────────

  private def walkClassMethods(
      cls: ClassSymbol,
      path: List[NestingNode],
      fieldCtx: Map[String, TypeOrMethodic],
      results: ListBuffer[FoundUsage],
  ): Unit =
    cls.declarations.foreach {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        ts.tree.foreach {
          case defDef: DefDef =>
            val methodNode = NestingNode.Method(methodName, Some(defDef))
            val methodPath = path :+ methodNode
            defDef.rhs.foreach { rhs =>
              walkTree(rhs, methodPath, fieldCtx, results)
            }
          case _              =>
        }
      case _                                                        =>
    }

  // ── Tree walker ──────────────────────────────────────────────────────────

  private def walkTree(
      tree: Tree,
      path: List[NestingNode],
      fieldCtx: Map[String, TypeOrMethodic],
      results: ListBuffer[FoundUsage],
  ): Unit = {
    // Check for method calls at this node
    checkMethodCall(tree, path, fieldCtx, results)

    // Recurse into children — carefully avoiding double-matching of call patterns.
    // When we match Apply(Select(receiver, method), args) as a method call,
    // we must NOT recurse into the Select(receiver, method) as a standalone Select
    // (which would produce a duplicate no-arg call match). Instead, recurse only
    // into the receiver and args.
    tree match {
      // Nested class (anonymous classes in method bodies)
      case classDef: ClassDef =>
        val className = classDef.name.toString.stripSuffix("$")
        val classNode = NestingNode.ClassOrObject(className, isModule = false, Some(classDef))
        val classPath = path :+ classNode

        // Check inheritance on nested class
        if (inheritanceSearches.nonEmpty) {
          checkNestedClassInheritance(classDef, classPath, results)
        }

        // Build extended field context with nested class's own fields
        val nestedFieldCtx = fieldCtx ++ extractFieldsFromClassDef(classDef)

        // Walk nested class method bodies
        classDef.rhs.body.foreach {
          case defDef: DefDef =>
            val methodName = defDef.name.toString
            if (!methodName.startsWith("<") && !methodName.startsWith("$")) {
              val methodNode = NestingNode.Method(methodName, Some(defDef))
              val methodPath = classPath :+ methodNode
              defDef.rhs.foreach { rhs =>
                walkTree(rhs, methodPath, nestedFieldCtx, results)
              }
            }
          case other          =>
            // Walk non-method body items (e.g., ValDefs) for nested classes
            walkTree(other, classPath, nestedFieldCtx, results)
        }

      case Block(stats, expr) =>
        // Before recursing, collect local val types from this block for field context
        val blockFieldCtx = fieldCtx ++ extractFieldsFromBlock(stats)
        stats.foreach(walkTree(_, path, blockFieldCtx, results))
        walkTree(expr, path, blockFieldCtx, results)

      case t: ValDef           => t.rhs.foreach(walkTree(_, path, fieldCtx, results))
      case t: DefDef           => t.rhs.foreach(walkTree(_, path, fieldCtx, results))
      case l: Lambda           => walkTree(l.meth, path, fieldCtx, results)
      case Inlined(body, _, _) => walkTree(body, path, fieldCtx, results)

      // Apply(Select(receiver, method), args) — recurse into receiver + args, skip Select
      case Apply(Select(qual, _), args)               =>
        walkTree(qual, path, fieldCtx, results)
        args.foreach(walkTree(_, path, fieldCtx, results))
      // Apply(TypeApply(Select(receiver, method), _), args) — recurse into receiver + args
      case Apply(TypeApply(Select(qual, _), _), args) =>
        walkTree(qual, path, fieldCtx, results)
        args.foreach(walkTree(_, path, fieldCtx, results))
      case Apply(fun, args)                           =>
        walkTree(fun, path, fieldCtx, results)
        args.foreach(walkTree(_, path, fieldCtx, results))
      case TypeApply(Select(qual, _), _)              =>
        walkTree(qual, path, fieldCtx, results)
      case TypeApply(fun, _)                          =>
        walkTree(fun, path, fieldCtx, results)
      case Select(qual, _)                            =>
        walkTree(qual, path, fieldCtx, results)

      case _ => ()
    }
  }

  // ── Method call checking ─────────────────────────────────────────────────

  /** Check if a tree node represents a method call matching any MethodCall search. */
  private def checkMethodCall(
      tree: Tree,
      path: List[NestingNode],
      fieldCtx: Map[String, TypeOrMethodic],
      results: ListBuffer[FoundUsage],
  ): Unit = tree match {

    // Constructor: Apply(Select(New(TypeTree), "<init>"), args)
    case Apply(Select(newTree @ New(typeTree), _), args) =>
      try {
        val tpe = typeTree.toType
        val fqn = TastyUtils.extractFqn(tpe)
        fqn.foreach { ownerFqn =>
          methodCallSearches.foreach { search =>
            if (TypeMatcherResolver.matchesFqn(search.ownerType, ownerFqn)) {
              val simpleName = TastyUtils.extractTypeName(tpe).getOrElse(ownerFqn.split('.').last)
              results += FoundUsage.MethodCallResult(
                search = search,
                path = NestingPath(path),
                tree = tree,
                ownerFqn = ownerFqn,
                ownerSimpleName = simpleName,
                methodName = "<init>",
                args = extractLiteralArgs(args),
                receiverTree = newTree,
              )
            }
          }
        }
      } catch { case _: Exception => }

    // Regular call: Apply(Select(receiver, method), args)
    case Apply(Select(receiver, methodName), args) if !receiver.isInstanceOf[New] =>
      val name = TastyUtils.simpleName(methodName)
      tryMatchReceiver(receiver, name, args, tree, path, fieldCtx, results)

    // Generic call: Apply(TypeApply(Select(receiver, method), _), args)
    case Apply(TypeApply(Select(receiver, methodName), _), args) if !receiver.isInstanceOf[New] =>
      val name = TastyUtils.simpleName(methodName)
      tryMatchReceiver(receiver, name, args, tree, path, fieldCtx, results)

    // Imported member call: Apply(Ident(importedName), args)
    // Handles `importedFlexiFlow(settings)` where `flexiFlow` was imported from `Producer`.
    // The Ident's TermRef prefix chain contains the owning type.
    case Apply(ident: Ident, args) =>
      tryMatchIdent(ident, args, tree, path, results)

    // Imported member call with type args: Apply(TypeApply(Ident(importedName), _), args)
    case Apply(TypeApply(ident: Ident, _), args) =>
      tryMatchIdent(ident, args, tree, path, results)

    // Standalone Ident (no-arg imported reference)
    case ident: Ident =>
      tryMatchIdent(ident, Nil, tree, path, results)

    case _ => ()
  }

  /** Try to resolve receiver type and match against MethodCall searches. */
  private def tryMatchReceiver(
      receiver: Tree,
      methodName: String,
      args: List[Tree],
      fullTree: Tree,
      path: List[NestingNode],
      fieldCtx: Map[String, TypeOrMethodic],
      results: ListBuffer[FoundUsage],
  ): Unit = {
    // Strategy 1: Field pre-scan (Ident or Select(This, field))
    val fieldType = receiver match {
      case Ident(name)           => fieldCtx.get(TastyUtils.simpleName(name))
      case Select(_: This, name) => fieldCtx.get(TastyUtils.simpleName(name))
      case _                     => None
    }

    fieldType.foreach { tpe =>
      val fqn = TastyUtils.extractFqn(tpe)
      fqn.foreach { ownerFqn =>
        methodCallSearches.foreach { search =>
          if (TypeMatcherResolver.matches(search.ownerType, tpe)) {
            val simpleName = TastyUtils.extractTypeName(tpe).getOrElse(ownerFqn.split('.').last)
            results += FoundUsage.MethodCallResult(
              search = search,
              path = NestingPath(path),
              tree = fullTree,
              ownerFqn = ownerFqn,
              ownerSimpleName = simpleName,
              methodName = methodName,
              args = extractLiteralArgs(args),
              receiverTree = receiver,
            )
          }
        }
      }
    }

    // Strategy 2: TermRef resolution (for companion objects, imported members)
    if (fieldType.isEmpty) {
      try {
        receiver match {
          case trt: TermReferenceTree =>
            val refType = trt.referenceType
            checkTermRefChain(refType, methodName, args, fullTree, receiver, path, results)
          case _                      =>
        }
      } catch { case _: Exception => }
    }
  }

  /** Walk the TermRef chain checking each level against MethodCall searches. */
  private def checkTermRefChain(
      ref: Any,
      methodName: String,
      args: List[Tree],
      fullTree: Tree,
      receiverTree: Tree,
      path: List[NestingNode],
      results: ListBuffer[FoundUsage],
  ): Unit = ref match {
    case tr: TermRef =>
      TypeMatcherResolver.termRefFqn(tr).foreach { fqn =>
        methodCallSearches.foreach { search =>
          if (TypeMatcherResolver.matchesFqn(search.ownerType, fqn)) {
            val simpleName = fqn.split('.').last
            results += FoundUsage.MethodCallResult(
              search = search,
              path = NestingPath(path),
              tree = fullTree,
              ownerFqn = fqn,
              ownerSimpleName = simpleName,
              methodName = methodName,
              args = extractLiteralArgs(args),
              receiverTree = receiverTree,
            )
          }
        }
      }
      // Continue walking the prefix chain
      checkTermRefChain(tr.prefix, methodName, args, fullTree, receiverTree, path, results)
    case _           =>
  }

  /** Match an Ident node by walking its TermRef prefix chain. Handles imported member references like `importedFlexiFlow(settings)` where `flexiFlow`
    * was imported from `Producer` — the Ident's TermRef prefix contains `Producer`. Only matches when the prefix is a TermRef or PackageRef (imported
    * members), not ThisType (local fields — those are handled by tryMatchReceiver via field pre-scan).
    */
  private def tryMatchIdent(
      ident: Ident,
      args: List[Tree],
      fullTree: Tree,
      path: List[NestingNode],
      results: ListBuffer[FoundUsage],
  ): Unit =
    try {
      ident.referenceType match {
        case tr: TermRef =>
          // Skip local field references (ThisType prefix) — they are handled by
          // tryMatchReceiver via field pre-scan in the Apply(Select(Ident(field), method), args) path.
          tr.prefix match {
            case _: ThisType => () // local field, skip
            case _           => checkIdentRefChain(tr, args, fullTree, ident, path, results)
          }
        case _           =>
      }
    } catch { case _: Exception => }

  /** Walk the TermRef chain for an Ident, checking at each level. The Ident itself is the method name; the prefix is the owning type.
    */
  private def checkIdentRefChain(
      ref: Any,
      args: List[Tree],
      fullTree: Tree,
      identTree: Ident,
      path: List[NestingNode],
      results: ListBuffer[FoundUsage],
  ): Unit = ref match {
    case tr: TermRef =>
      // The TermRef name is the method, the prefix may contain the owner
      val methodName = tr.name.toString
      tr.prefix match {
        case prefixTr: TermRef =>
          TypeMatcherResolver.termRefFqn(prefixTr).foreach { fqn =>
            methodCallSearches.foreach { search =>
              if (TypeMatcherResolver.matchesFqn(search.ownerType, fqn)) {
                val simpleName = fqn.split('.').last
                results += FoundUsage.MethodCallResult(
                  search = search,
                  path = NestingPath(path),
                  tree = fullTree,
                  ownerFqn = fqn,
                  ownerSimpleName = simpleName,
                  methodName = methodName,
                  args = extractLiteralArgs(args),
                  receiverTree = identTree,
                )
              }
            }
          }
          // Continue walking deeper prefixes
          checkIdentRefChain(prefixTr, args, fullTree, identTree, path, results)
        case pr: PackageRef    =>
          val pkgName =
            try pr.symbol.fullName.toString
            catch { case _: Exception => "" }
          if (pkgName.nonEmpty) {
            val fqn = s"$pkgName.${tr.name.toString.stripSuffix("$")}"
            methodCallSearches.foreach { search =>
              if (TypeMatcherResolver.matchesFqn(search.ownerType, fqn)) {
                val simpleName = fqn.split('.').last
                results += FoundUsage.MethodCallResult(
                  search = search,
                  path = NestingPath(path),
                  tree = fullTree,
                  ownerFqn = fqn,
                  ownerSimpleName = simpleName,
                  methodName = methodName,
                  args = extractLiteralArgs(args),
                  receiverTree = identTree,
                )
              }
            }
          }
        case _                 =>
      }
    case _           =>
  }

  // ── Nested class inheritance ─────────────────────────────────────────────

  private def checkNestedClassInheritance(
      classDef: ClassDef,
      path: List[NestingNode],
      results: ListBuffer[FoundUsage],
  ): Unit = {
    classDef.rhs.parents.foreach { parentTree =>
      try {
        val parentType = parentTree match {
          case Apply(Select(New(typeTree), _), _)               => Some(typeTree.toType)
          case Apply(TypeApply(Select(New(typeTree), _), _), _) => Some(typeTree.toType)
          case typeTree: TypeTree                               => Some(typeTree.toType)
          case _                                                => None
        }
        parentType.foreach { tpe =>
          val fqn = TastyUtils.extractFqn(tpe)
          fqn.foreach { parentFqn =>
            inheritanceSearches.foreach { search =>
              if (TypeMatcherResolver.matches(search.parentType, tpe)) {
                val simpleName       = TastyUtils.extractTypeName(tpe).getOrElse(parentFqn.split('.').last)
                val inheritedMethods = resolveParentMethods(tpe)
                results += FoundUsage.InheritanceResult(
                  search = search,
                  path = NestingPath(path),
                  tree = classDef,
                  parentFqn = parentFqn,
                  parentSimpleName = simpleName,
                  inheritedMethods = inheritedMethods,
                )
              }
            }
          }
        }
      } catch { case _: Exception => }
    }
  }

  // ── Field extraction from trees ──────────────────────────────────────────

  private def extractFieldsFromClassDef(classDef: ClassDef): Map[String, TypeOrMethodic] = {
    val fields = scala.collection.mutable.Map.empty[String, TypeOrMethodic]
    classDef.rhs.body.foreach {
      case valDef: ValDef =>
        try {
          val name = valDef.name.toString
          if (!name.startsWith("<") && !name.startsWith("$")) {
            fields += (name -> valDef.symbol.declaredType)
          }
        } catch { case _: Exception => }
      case _              =>
    }
    fields.toMap
  }

  private def extractFieldsFromBlock(stats: List[Tree]): Map[String, TypeOrMethodic] = {
    val fields = scala.collection.mutable.Map.empty[String, TypeOrMethodic]
    stats.foreach {
      case valDef: ValDef =>
        try {
          val name = valDef.name.toString
          if (!name.startsWith("<") && !name.startsWith("$")) {
            fields += (name -> valDef.symbol.declaredType)
          }
        } catch { case _: Exception => }
      case _              =>
    }
    fields.toMap
  }

  // ── Literal extraction ───────────────────────────────────────────────────

  private def extractLiteralArgs(args: List[Tree]): Map[String, Option[LiteralValue]] =
    args.zipWithIndex.map { case (arg, idx) =>
      s"arg$idx" -> extractLiteral(arg)
    }.toMap

  private def extractLiteral(tree: Tree): Option[LiteralValue] = tree match {
    case Literal(c) =>
      c.value match {
        case s: String  => Some(LiteralValue.StringLit(s))
        case i: Int     => Some(LiteralValue.IntLit(i))
        case l: Long    => Some(LiteralValue.LongLit(l))
        case d: Double  => Some(LiteralValue.DoubleLit(d))
        case b: Boolean => Some(LiteralValue.BoolLit(b))
        case _          => None
      }
    case _          => None
  }
}

object SymbolUsageFinder {

  /** An enumerated method body with its ref, rhs tree, and declared return type (if available). */
  case class MethodBody(
      ref: MethodRef,
      rhs: Tree,
      declaredType: Option[TypeOrMethodic],
  )

  /** Enumerate all method bodies uniformly (including anonymous classes). Returns MethodBody entries for each method body found. Top-level methods
    * include their declared type; anonymous class methods have None.
    */
  def enumerateMethodBodies(packages: List[String])(using ctx: Context): List[MethodBody] =
    packages.flatMap(enumeratePackage)

  private def enumeratePackage(packageName: String)(using ctx: Context): List[MethodBody] = {
    val pkg           = ctx.findPackage(packageName)
    val userWithPkg   = TastyUtils.userClassesRecursive(pkg)
    val moduleWithPkg = TastyUtils.moduleClassesRecursive(pkg)
    val allWithPkg    = userWithPkg ++ moduleWithPkg
    allWithPkg.flatMap { case (ownerPkg, cls) =>
      val pkgName   = ownerPkg.fullName.toString
      val className = cls.name.toString.stripSuffix("$")
      enumerateClassMethods(pkgName, className, cls) ++
        enumerateAnonymousClassMethods(pkgName, className, cls)
    }
  }

  private def enumerateClassMethods(
      pkgName: String,
      className: String,
      cls: ClassSymbol,
  )(using Context): List[MethodBody] =
    cls.declarations.collect {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        val declType   =
          try Some(ts.declaredType)
          catch { case _: Exception => None }
        ts.tree.toList.flatMap {
          case defDef: DefDef =>
            defDef.rhs.toList.map(rhs => MethodBody(MethodRef(pkgName, className, methodName), rhs, declType))
          case _              => Nil
        }
    }.flatten

  /** Walk method bodies looking for anonymous ClassDef nodes and enumerate their methods. */
  private def enumerateAnonymousClassMethods(
      pkgName: String,
      className: String,
      cls: ClassSymbol,
  )(using Context): List[MethodBody] = {
    val results = ListBuffer.empty[MethodBody]
    cls.declarations.foreach {
      case ts: TermSymbol =>
        ts.tree.foreach {
          case defDef: DefDef =>
            defDef.rhs.foreach { rhs =>
              collectAnonClassMethods(rhs, pkgName, className, results)
            }
          case _              =>
        }
      case _              =>
    }
    results.toList
  }

  private def collectAnonClassMethods(
      tree: Tree,
      pkgName: String,
      className: String,
      results: ListBuffer[MethodBody],
  ): Unit = tree match {
    case Block(stats, expr) =>
      stats.foreach(collectAnonClassMethods(_, pkgName, className, results))
      collectAnonClassMethods(expr, pkgName, className, results)

    case classDef: ClassDef =>
      classDef.rhs.body.foreach {
        case defDef: DefDef =>
          val methodName = defDef.name.toString
          if (!methodName.startsWith("<") && !methodName.startsWith("$")) {
            defDef.rhs.foreach { rhs =>
              results += MethodBody(MethodRef(pkgName, className, methodName), rhs, None)
            }
          }
        case _              =>
      }

    case _ =>
  }
}
