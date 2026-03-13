package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Traversers.*
import tastyquery.Trees.*
import tastyquery.Types.*

// ============================================================================
// TASTy-based Slick Scanner
//
// Scans compiled Scala code via TASTy to find Slick database operations.
// Output: "classA.methodB reads/writes tableC"
//
// Two-pass approach:
//   1. Pre-scan: build Table class → table name map (from parent constructor
//      string literal) and TableQuery field → table name map.
//      Handles both top-level classes and anonymous classes (factory pattern).
//      When a Table constructor arg is a method parameter, the table name is
//      resolved via ParamValueIndex (backward param propagation).
//   2. Main scan: use SymbolUsageFinder with type-based searches to detect
//      Slick operations. Type resolution uses Strategy 4 (return-type
//      propagation via SignedName signatures) for extension method chains.
//
// Lifted embedding detection (via extension method receiver types):
//   BasicStreamingQueryActionExtensionMethodsImpl.result → Read
//   InsertActionExtensionMethodsImpl.insertOrUpdate/++=  → Write
//   DeleteActionExtensionMethodsImpl.delete               → Write
//
// Plain SQL detection:
//   SQLActionBuilder.as[T]                                → Read
//   StringContext.sqlu                                     → Write
//   StringContext.s (when SQL-like, e.g. SimpleDBIO)       → inferred from SQL
// ============================================================================

class TastySlickScanner(
    tableBaseTypeName: String = "Table",
)(using ctx: Context) extends IntegrationScanner {

  // Type-based searches for Slick patterns
  private val liftedReadSearch = SymbolSearch.MethodCall(
    TypeMatcher.fqnEndsWith("BasicStreamingQueryActionExtensionMethodsImpl"),
  )
  private val liftedInsertSearch = SymbolSearch.MethodCall(
    TypeMatcher.fqnEndsWith("InsertActionExtensionMethodsImpl"),
  )
  // InsertActionComposerImpl provides insertOrUpdate/insertOrUpdateAll.
  // Also matches slick-pg's ExtPostgresInsertActionComposerImpl (endsWith match).
  private val liftedInsertComposerSearch = SymbolSearch.MethodCall(
    TypeMatcher.fqnEndsWith("InsertActionComposerImpl"),
  )
  private val liftedDeleteSearch = SymbolSearch.MethodCall(
    TypeMatcher.fqnEndsWith("DeleteActionExtensionMethodsImpl"),
  )
  private val plainSqlReadSearch = SymbolSearch.MethodCall(
    TypeMatcher.fqnEndsWith("SQLActionBuilder"),
  )
  private val plainSqlWriteSearch = SymbolSearch.MethodCall(
    TypeMatcher.fqnEndsWith("StringContext"),
  )

  private val readMethods = Set("result")
  private val insertWriteMethods = Set("insertOrUpdate", "insertOrUpdateAll", "++=", "+=", "update", "forceInsert", "forceInsertAll")
  private val deleteMethods = Set("delete")

  def scan(packages: List[String]): List[DiscoveredIntegration] =
    packages.flatMap(scanPackage)

  private def scanPackage(packageName: String): List[DiscoveredIntegration] = {
    val pkg = ctx.findPackage(packageName)
    val classesWithPkg = TastyUtils.userClassesRecursive(pkg)
    val modulesWithPkg = TastyUtils.moduleClassesRecursive(pkg)

    // Phase 1: build Table class → table name and field → table name maps
    // Include classes nested inside modules (e.g., object Foo { class MyTable extends Table(...) })
    val nestedInModules = TastyUtils.nestedClassesInModules(pkg).map(_._2)
    val nestedInClasses = classesWithPkg.flatMap { case (_, cls) =>
      cls.declarations.collect { case cs: ClassSymbol => cs }
    }
    val allTableCandidates = classesWithPkg.map(_._2) ++ nestedInModules ++ nestedInClasses
    val topLevelTableClassToName = buildTableClassMap(allTableCandidates)
    val topLevelFieldToTableName = buildFieldToTableMap(modulesWithPkg.map(_._2), topLevelTableClassToName)

    // Build anon field map, collecting deferred entries (param-based table names)
    val deferredFieldToFactory = scala.collection.mutable.Map.empty[String, (MethodRef, Int)]
    val anonFieldToTableName   = buildAnonFieldToTableMap(modulesWithPkg, classesWithPkg, deferredFieldToFactory)
    val fieldToTableName       = topLevelFieldToTableName ++ anonFieldToTableName

    // Resolve deferred table names via backward param propagation
    val fieldToTableMulti: Map[String, Set[String]] =
      if (deferredFieldToFactory.isEmpty) Map.empty
      else {
        val index = ParamValueIndex.build(List(packageName))
        deferredFieldToFactory.flatMap { case (fieldName, (factoryRef, paramIdx)) =>
          val resolved = index.resolve(
            factoryRef.packageName, factoryRef.className, factoryRef.methodName, paramIdx,
          )
          if (resolved.nonEmpty) Some(fieldName -> resolved) else None
        }.toMap
      }

    // Phase 2: find Slick operations via SymbolUsageFinder
    val finder = new SymbolUsageFinder(
      Seq(liftedReadSearch, liftedInsertSearch, liftedInsertComposerSearch, liftedDeleteSearch, plainSqlReadSearch, plainSqlWriteSearch),
    )
    val usages = finder.findAll(List(packageName))

    // Pre-compute val bindings per enclosing scope (class body + method body)
    // for resolving variable references in plain SQL interpolations.
    val valBindingsCache = scala.collection.mutable.Map.empty[List[NestingNode], Map[String, Tree]]

    usages.collect { case u: FoundUsage.MethodCallResult => u }.flatMap { u =>
      classifyUsage(u) match {
        case None => Nil
        case Some(accessType) =>
          u.search match {
            // Lifted embedding: resolve table name(s) from receiver tree
            case `liftedReadSearch` | `liftedInsertSearch` | `liftedInsertComposerSearch` | `liftedDeleteSearch` =>
              val tables = resolveTableNames(u.receiverTree, fieldToTableName, fieldToTableMulti)
              tables.toList.map { table =>
                val evidence = s"$table.${u.methodName}"
                mkIntegration(u.path.toMethodRef, accessType, table, evidence)
              }
            // Plain SQL: extract SQL from the tree, resolving interpolation variables where possible
            case `plainSqlReadSearch` | `plainSqlWriteSearch` =>
              val valBindings = valBindingsCache.getOrElseUpdate(
                u.path.nodes,
                collectValBindings(u.path),
              )
              val rawSql = SqlUtils.sqlFromInterpolation(u.tree, valBindings)
                .orElse(SqlUtils.sqlFrom(u.tree, valBindings))
                .orElse(SqlUtils.sqlFrom(u.receiverTree, valBindings))
              // Strip Slick's # splice markers (e.g., #$tableName → tableName) before parsing
              val sql = rawSql.map(_.replaceAll("#(?=\\w)", ""))
              sql.filter(SqlUtils.looksLikeSql).flatMap { s =>
                // For s"..." interpolations, require the string to actually start with a SQL keyword.
                // This avoids false positives from strings like s"Getting data from $source".
                val refinedAccess = if (u.methodName == "s") inferAccessTypeFromSql(s) else Some(accessType)
                refinedAccess.map(at => mkIntegration(u.path.toMethodRef, at, SqlUtils.extractTableName(s), s))
              }.toList
            case _ => Nil
          }
      }
    }.distinct
  }

  private def classifyUsage(u: FoundUsage.MethodCallResult): Option[DataAccessType] = {
    val method = u.methodName
    u.search match {
      case `liftedReadSearch` =>
        if (readMethods.contains(method)) Some(DataAccessType.Read)
        else None
      case `liftedInsertSearch` | `liftedInsertComposerSearch` =>
        if (insertWriteMethods.contains(method)) Some(DataAccessType.Write)
        else None
      case `liftedDeleteSearch` =>
        if (deleteMethods.contains(method)) Some(DataAccessType.Write)
        else None
      case `plainSqlReadSearch` =>
        if (method == "as") Some(DataAccessType.Read)
        else None
      case `plainSqlWriteSearch` =>
        if (method == "sqlu") Some(DataAccessType.Write)
        // Standard s"..." interpolation — may contain raw SQL (e.g., SimpleDBIO patterns).
        // Access type is tentative; refined by inferAccessTypeFromSql after SQL extraction.
        else if (method == "s") Some(DataAccessType.Write)
        else None
      case _ => None
    }
  }

  // ── SQL access type inference ────────────────────────────────────────────

  /** Infer Read/Write from raw SQL content (for s"..." interpolations used with SimpleDBIO etc.). */
  private def inferAccessTypeFromSql(sql: String): Option[DataAccessType] = {
    val firstWord = sql.trim.split("\\s+").headOption.map(_.toUpperCase).getOrElse("")
    firstWord match {
      case "SELECT"   => Some(DataAccessType.Read)
      case "INSERT" | "UPDATE" | "DELETE" | "TRUNCATE" | "UPSERT" | "MERGE" => Some(DataAccessType.Write)
      case _          => None
    }
  }

  // ── Pre-scan: Table class → table name ───────────────────────────────────

  /** Extract table name from Table subclass parent constructor: Table[T](tag, "table_name"). */
  private def buildTableClassMap(classes: List[ClassSymbol]): Map[String, String] =
    classes.flatMap { cls =>
      cls.tree.toList.flatMap { classDef =>
        classDef.rhs.parents.flatMap(extractTableNameFromParent(_, cls.name.toString))
          .headOption.map(cls.name.toString -> _)
      }
    }.toMap

  private def extractTableNameFromParent(tree: Tree, className: String, valBindings: Map[String, Tree] = Map.empty): Option[String] = tree match {
    case Apply(fun, args) =>
      if (isTableInit(fun)) {
        val fromLiteral = args.collectFirst { case Literal(c) if c.value.isInstanceOf[String] => c.value.asInstanceOf[String] }
        fromLiteral
          .orElse {
            // Try resolving non-literal args via val bindings (e.g., val tableName = "my_table")
            if (valBindings.nonEmpty)
              args.iterator.flatMap(SqlUtils.resolveToString(_, valBindings)).nextOption()
            else None
          }
          .orElse {
            if (className.nonEmpty) Some(TastySlickScanner.unresolvedTableName(className)) else None
          }
      } else
        None
    case _ => None
  }

  private def isTableInit(tree: Tree): Boolean = tree match {
    case TypeApply(inner, _) => isTableInit(inner)
    case Select(New(typeTree), _) =>
      try {
        TastyUtils.extractTypeName(typeTree.toType).exists(_.contains(tableBaseTypeName))
      } catch { case _: Exception => false }
    case _ => false
  }

  /** If a Table constructor arg is a method parameter, return the flat parameter index. */
  private def detectMethodParamInTableInit(tree: Tree, methodParams: Map[String, Int]): Option[Int] = tree match {
    case Apply(fun, args) if isTableInit(fun) =>
      args.iterator.collectFirst {
        case Ident(name) if methodParams.contains(TastyUtils.simpleName(name)) =>
          methodParams(TastyUtils.simpleName(name))
      }
    case _ => None
  }

  // ── Pre-scan: TableQuery field → table name ──────────────────────────────

  private def buildFieldToTableMap(modules: List[ClassSymbol], tableClassToName: Map[String, String]): Map[String, String] =
    modules.flatMap { mod =>
      mod.declarations.collect {
        case ts: TermSymbol if !ts.name.toString.startsWith("<") =>
          extractTableQueryClassName(ts.declaredType).flatMap { tableClassName =>
            tableClassToName.get(tableClassName).map(ts.name.toString -> _)
          }
      }.flatten
    }.toMap

  private def extractTableQueryClassName(tpe: TypeOrMethodic): Option[String] = tpe match {
    case at: AppliedType =>
      TastyUtils.extractTypeRef(at) match {
        case Some(tr) if tr.name.toString == "TableQuery" =>
          at.args.headOption.flatMap {
            case argRef: TypeRef => Some(argRef.name.toString)
            case _               => None
          }
        case _ => None
      }
    case _ => None
  }

  // ── Pre-scan: anonymous class Table/TableQuery discovery ──────────────────

  private def buildAnonFieldToTableMap(
      modulesWithPkg: List[(PackageSymbol, ClassSymbol)],
      classesWithPkg: List[(PackageSymbol, ClassSymbol)],
      deferredFieldToFactory: scala.collection.mutable.Map[String, (MethodRef, Int)],
  ): Map[String, String] = {
    val result = scala.collection.mutable.Map.empty[String, String]
    for ((ownerPkg, mod) <- modulesWithPkg) {
      forEachModuleMethodBodyWithCtx(ownerPkg, mod) { (methodRef, methodParams, rhs) =>
        new AnonTableMappingCollector(result, Some(methodRef), methodParams, deferredFieldToFactory).traverse(rhs)
      }
      forEachNestedClassBody(mod) { tree =>
        new AnonTableMappingCollector(result).traverse(tree)
      }
    }
    // Also scan regular user class bodies for nested Table + TableQuery patterns
    // (e.g., class Repo { class MyTable extends Table(...); val q = TableQuery[MyTable] })
    for ((_, cls) <- classesWithPkg) {
      cls.tree.toList.foreach { classDef =>
        collectTableMappingsFromScope(classDef.rhs.body, result)
      }
    }
    result.toMap
  }

  /** TreeTraverser that finds anonymous ClassDef and Block scopes containing
    * Table subclass + TableQuery bindings inside all tree types.
    */
  private class AnonTableMappingCollector(
      result: scala.collection.mutable.Map[String, String],
      enclosingMethod: Option[MethodRef] = None,
      methodParams: Map[String, Int] = Map.empty,
      deferredFieldToFactory: scala.collection.mutable.Map[String, (MethodRef, Int)] = scala.collection.mutable.Map.empty,
  ) extends TreeTraverser {
    override def traverse(tree: Tree): Unit = tree match {
      case Block(stats, expr) =>
        val items = stats :+ expr
        collectTableMappingsFromScope(items, result, enclosingMethod, methodParams, deferredFieldToFactory)
        super.traverse(tree)
      case classDef: ClassDef =>
        collectTableMappingsFromScope(classDef.rhs.body, result, enclosingMethod, methodParams, deferredFieldToFactory)
      case _ =>
        super.traverse(tree)
    }
  }

  private def collectTableMappingsFromScope(
      items: List[Tree],
      result: scala.collection.mutable.Map[String, String],
      enclosingMethod: Option[MethodRef] = None,
      methodParams: Map[String, Int] = Map.empty,
      deferredFieldToFactory: scala.collection.mutable.Map[String, (MethodRef, Int)] = scala.collection.mutable.Map.empty,
  ): Unit = {
    val tableClassToName   = scala.collection.mutable.Map.empty[String, String]
    val tableClassDeferred = scala.collection.mutable.Map.empty[String, (MethodRef, Int)]

    // Collect val bindings from this scope for resolving Table constructor args
    val scopeBindings: Map[String, Tree] = items.collect {
      case vd: ValDef => vd.rhs.map(rhs => vd.name.toString -> rhs)
    }.flatten.toMap

    items.foreach {
      case classDef: ClassDef =>
        // Also collect val bindings from the class body (for nested Table classes)
        val classBindings: Map[String, Tree] = classDef.rhs.body.collect {
          case vd: ValDef => vd.rhs.map(rhs => vd.name.toString -> rhs)
        }.flatten.toMap
        val allBindings = scopeBindings ++ classBindings
        classDef.rhs.parents.foreach { parent =>
          extractTableNameFromParent(parent, classDef.name.toString, allBindings) match {
            case Some(name) =>
              if (name.startsWith("<unresolved:") && enclosingMethod.isDefined) {
                // Check if any constructor arg is a method parameter — defer resolution
                detectMethodParamInTableInit(parent, methodParams) match {
                  case Some(paramIdx) =>
                    tableClassDeferred(classDef.name.toString) = (enclosingMethod.get, paramIdx)
                  case None =>
                    // No method param found; keep the unresolved sentinel
                    tableClassToName(classDef.name.toString) = name
                }
              } else {
                tableClassToName(classDef.name.toString) = name
              }
            case None =>
          }
        }
      case _ =>
    }

    items.foreach {
      case valDef: ValDef =>
        val fieldName = valDef.name.toString
        valDef.rhs.foreach { rhs =>
          extractTableQueryClassFromTree(rhs).foreach { tableClassName =>
            tableClassToName.get(tableClassName) match {
              case Some(tableName) =>
                result += (fieldName -> tableName)
              case None =>
                tableClassDeferred.get(tableClassName).foreach { deferred =>
                  deferredFieldToFactory += (fieldName -> deferred)
                }
            }
          }
        }
        try {
          valDef.symbol.declaredType match {
            case at: AppliedType =>
              extractTableQueryClassName(at).foreach { tableClassName =>
                tableClassToName.get(tableClassName) match {
                  case Some(tableName) =>
                    result += (fieldName -> tableName)
                  case None =>
                    tableClassDeferred.get(tableClassName).foreach { deferred =>
                      deferredFieldToFactory += (fieldName -> deferred)
                    }
                }
              }
            case _ =>
          }
        } catch { case _: Exception => }
      case _ =>
    }
  }

  private def extractTableQueryClassFromTree(tree: Tree): Option[String] = tree match {
    case TypeApply(_, typeArgs) =>
      typeArgs.headOption.flatMap { t =>
        try {
          t.toType match {
            case tr: TypeRef     => Some(tr.name.toString)
            case at: AppliedType => TastyUtils.extractTypeRef(at).map(_.name.toString)
            case _               => None
          }
        } catch { case _: Exception => None }
      }
    case Apply(fun, _) => extractTableQueryClassFromTree(fun)
    case _             => None
  }

  // ── Table name resolution ────────────────────────────────────────────────

  /** Resolve table name(s) from a usage receiver tree.
    *
    * Returns a non-empty set of table names on success, or a singleton set with the
    * `<unresolved:ClassName>` sentinel when the table is known but the name was not
    * resolvable at compile time, or an empty set when nothing could be determined.
    */
  private def resolveTableNames(
      tree: Tree,
      fieldToTable: Map[String, String],
      fieldToTableMulti: Map[String, Set[String]],
  ): Set[String] = {
    findFirstTableRef(tree, fieldToTable) match {
      case Some(name) if !name.startsWith("<unresolved:") =>
        // Common case: resolved via single-value map
        Set(name)
      case singleResult =>
        // Try multi-value map (deferred resolution result)
        val multiResult = findFirstTableRefSet(tree, fieldToTableMulti)
        if (multiResult.nonEmpty) multiResult
        else singleResult.map(Set(_)).getOrElse(Set.empty)
    }
  }

  private def findFirstTableRef(tree: Tree, fieldToTable: Map[String, String]): Option[String] = tree match {
    case Ident(name) => fieldToTable.get(TastyUtils.simpleName(name))
    case Select(qual, name) =>
      fieldToTable.get(TastyUtils.simpleName(name)).orElse(findFirstTableRef(qual, fieldToTable))
    case Apply(fun, args) =>
      findFirstTableRef(fun, fieldToTable).orElse(
        args.iterator.map(findFirstTableRef(_, fieldToTable)).collectFirst { case Some(v) => v },
      )
    case TypeApply(fun, _) => findFirstTableRef(fun, fieldToTable)
    case Block(stats, expr) =>
      stats.iterator.map(findFirstTableRef(_, fieldToTable)).collectFirst { case Some(v) => v }
        .orElse(findFirstTableRef(expr, fieldToTable))
    case _ => None
  }

  /** Variant of findFirstTableRef that returns a Set[String] from the multi-value map. */
  private def findFirstTableRefSet(tree: Tree, fieldToTableMulti: Map[String, Set[String]]): Set[String] = tree match {
    case Ident(name)        => fieldToTableMulti.getOrElse(TastyUtils.simpleName(name), Set.empty)
    case Select(qual, name) =>
      fieldToTableMulti.get(TastyUtils.simpleName(name))
        .getOrElse(findFirstTableRefSet(qual, fieldToTableMulti))
    case Apply(fun, args) =>
      findFirstTableRefSet(fun, fieldToTableMulti) match {
        case empty if empty.isEmpty =>
          args.iterator.map(findFirstTableRefSet(_, fieldToTableMulti)).find(_.nonEmpty).getOrElse(Set.empty)
        case found => found
      }
    case TypeApply(fun, _)  => findFirstTableRefSet(fun, fieldToTableMulti)
    case Block(stats, expr) =>
      stats.iterator.map(findFirstTableRefSet(_, fieldToTableMulti)).find(_.nonEmpty)
        .getOrElse(findFirstTableRefSet(expr, fieldToTableMulti))
    case _ => Set.empty
  }

  // ── Val binding collection ──────────────────────────────────────────────

  /** Collect val bindings from both the enclosing class body and the enclosing method body.
    * This allows resolution of interpolation variables like `#$tableName` where `tableName`
    * is a class field assigned to a string literal.
    */
  private def collectValBindings(path: NestingPath): Map[String, Tree] = {
    val bindings = scala.collection.mutable.Map.empty[String, Tree]

    // Collect class-level val bindings (e.g., private val tableName = "my_table")
    path.nodes.collect { case c: NestingNode.ClassOrObject => c }.foreach { classNode =>
      classNode.tree.foreach { classDef =>
        classDef.rhs.body.foreach {
          case vd: ValDef =>
            vd.rhs.foreach(rhs => bindings += (vd.name.toString -> rhs))
          case _ =>
        }
      }
    }

    // Collect method-level val bindings (local vals in the method body)
    path.nodes.reverse.collectFirst { case NestingNode.Method(_, Some(dd)) => dd }.foreach { defDef =>
      defDef.rhs.foreach(rhs => bindings ++= SqlUtils.collectTreeBindings(rhs))
    }

    bindings.toMap
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def forEachModuleMethodBodyWithCtx(
      ownerPkg: PackageSymbol,
      mod: ClassSymbol,
  )(f: (MethodRef, Map[String, Int], Tree) => Unit): Unit = {
    val pkgName = ownerPkg.fullName.toString
    val clsName = mod.name.toString.stripSuffix("$")
    for {
      ts     <- mod.declarations.collect { case ts: TermSymbol => ts }
      if !ts.isSynthetic
      defDef <- ts.tree.collect { case dd: DefDef => dd }
      rhs    <- defDef.rhs
    } {
      val methodName = TastyUtils.simpleName(ts.name)
      val methodRef  = MethodRef(pkgName, clsName, methodName)
      val paramMap   = defDef.paramLists
        .flatMap {
          case Left(params) => params.map(p => TastyUtils.simpleName(p.name))
          case Right(_)     => Nil
        }
        .toIndexedSeq.zipWithIndex
        .map { case (name, i) => name -> i }
        .toMap
      f(methodRef, paramMap, rhs)
    }
  }

  /** Traverse nested class declarations inside a module (e.g., case class Journal inside companion object).
    * These are ClassSymbol declarations whose bodies contain inner Table subclasses and TableQuery fields.
    */
  private def forEachNestedClassBody(mod: ClassSymbol)(f: Tree => Unit): Unit =
    for {
      cs <- mod.declarations.collect { case cs: ClassSymbol => cs }
      classDef <- cs.tree.toList
    } f(classDef)

  private def mkIntegration(method: MethodRef, access: DataAccessType, table: String, evidence: String): DiscoveredIntegration =
    DiscoveredIntegration(
      method = method,
      accessType = access,
      resourceType = ResourceType.Database,
      scanner = "slick",
      target = table,
      evidence = evidence,
    )
}

object TastySlickScanner {

  /** Construct the placeholder name used when a Slick Table class has a runtime (non-literal) table name.
    * Use this in [[LineageAdjustments]] resource renames to stay in sync with the scanner output.
    *
    * Prefer the `ClassTag` overload when the Table class is accessible as a type.
    * The string overload is needed when the Table class is an inner class of an anonymous class
    * (common with the `Repository.apply()` factory pattern).
    */
  def unresolvedTableName(className: String): String =
    s"<unresolved:$className>"

  /** Type-safe variant — extracts the simple class name from the ClassTag. */
  def unresolvedTableName[T: reflect.ClassTag]: String =
    unresolvedTableName(reflect.classTag[T].runtimeClass.getSimpleName)
}
