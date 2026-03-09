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
    val topLevelTableClassToName = buildTableClassMap(classesWithPkg.map(_._2))
    val topLevelFieldToTableName = buildFieldToTableMap(modulesWithPkg.map(_._2), topLevelTableClassToName)
    val anonFieldToTableName = buildAnonFieldToTableMap(modulesWithPkg)
    val fieldToTableName = topLevelFieldToTableName ++ anonFieldToTableName

    // Phase 2: find Slick operations via SymbolUsageFinder
    val finder = new SymbolUsageFinder(
      Seq(liftedReadSearch, liftedInsertSearch, liftedDeleteSearch, plainSqlReadSearch, plainSqlWriteSearch),
    )
    val usages = finder.findAll(List(packageName))

    usages.collect { case u: FoundUsage.MethodCallResult => u }.flatMap { u =>
      classifyUsage(u).flatMap { accessType =>
        u.search match {
          // Lifted embedding: resolve table name from receiver tree
          case `liftedReadSearch` | `liftedInsertSearch` | `liftedDeleteSearch` =>
            resolveTableName(u.receiverTree, fieldToTableName).map { table =>
              val evidence = s"$table.${u.methodName}"
              mkIntegration(u.path.toMethodRef, accessType, table, evidence)
            }
          // Plain SQL: extract SQL from the tree
          case `plainSqlReadSearch` | `plainSqlWriteSearch` =>
            val sql = SqlUtils.sqlFrom(u.tree).orElse(SqlUtils.sqlFrom(u.receiverTree))
            sql.filter(SqlUtils.looksLikeSql).map { s =>
              mkIntegration(u.path.toMethodRef, accessType, SqlUtils.extractTableName(s), s)
            }
          case _ => None
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
      case `liftedInsertSearch` =>
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
        else None
      case _ => None
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

  private def extractTableNameFromParent(tree: Tree, className: String): Option[String] = tree match {
    case Apply(fun, args) =>
      if (isTableInit(fun)) {
        val fromLiteral = args.collectFirst { case Literal(c) if c.value.isInstanceOf[String] => c.value.asInstanceOf[String] }
        fromLiteral.orElse {
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

  private def buildAnonFieldToTableMap(modulesWithPkg: List[(PackageSymbol, ClassSymbol)]): Map[String, String] = {
    val result = scala.collection.mutable.Map.empty[String, String]
    for ((_, mod) <- modulesWithPkg)
      forEachModuleMethodBody(mod)(collectAnonClassTableMappings(_, result))
    result.toMap
  }

  private def collectAnonClassTableMappings(tree: Tree, result: scala.collection.mutable.Map[String, String]): Unit = {
    new AnonTableMappingCollector(result).traverse(tree)
  }

  /** TreeTraverser that finds anonymous ClassDef and Block scopes containing
    * Table subclass + TableQuery bindings inside all tree types.
    */
  private class AnonTableMappingCollector(result: scala.collection.mutable.Map[String, String]) extends TreeTraverser {
    override def traverse(tree: Tree): Unit = tree match {
      case Block(stats, expr) =>
        val items = stats :+ expr
        collectTableMappingsFromScope(items, result)
        super.traverse(tree)
      case classDef: ClassDef =>
        collectTableMappingsFromScope(classDef.rhs.body, result)
      case _ =>
        super.traverse(tree)
    }
  }

  private def collectTableMappingsFromScope(items: List[Tree], result: scala.collection.mutable.Map[String, String]): Unit = {
    val tableClassToName = scala.collection.mutable.Map.empty[String, String]

    items.foreach {
      case classDef: ClassDef =>
        classDef.rhs.parents.foreach { parent =>
          extractTableNameFromParent(parent, classDef.name.toString).foreach { tableName =>
            tableClassToName += (classDef.name.toString -> tableName)
          }
        }
      case _ =>
    }

    items.foreach {
      case valDef: ValDef =>
        val fieldName = valDef.name.toString
        valDef.rhs.foreach { rhs =>
          extractTableQueryClassFromTree(rhs).foreach { tableClassName =>
            tableClassToName.get(tableClassName).foreach { tableName =>
              result += (fieldName -> tableName)
            }
          }
        }
        try {
          valDef.symbol.declaredType match {
            case at: AppliedType =>
              extractTableQueryClassName(at).foreach { tableClassName =>
                tableClassToName.get(tableClassName).foreach { tableName =>
                  result += (fieldName -> tableName)
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
      typeArgs.headOption.flatMap {
        case t: TypeTree =>
          try {
            t.toType match {
              case tr: TypeRef     => Some(tr.name.toString)
              case at: AppliedType => TastyUtils.extractTypeRef(at).map(_.name.toString)
              case _               => None
            }
          } catch { case _: Exception => None }
        case _ => None
      }
    case Apply(fun, _) => extractTableQueryClassFromTree(fun)
    case _             => None
  }

  // ── Table name resolution ────────────────────────────────────────────────

  private def resolveTableName(tree: Tree, fieldToTable: Map[String, String]): Option[String] =
    findFirstTableRef(tree, fieldToTable)

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

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def forEachModuleMethodBody(mod: ClassSymbol)(f: Tree => Unit): Unit =
    for {
      ts <- mod.declarations.collect { case ts: TermSymbol => ts }
      defDef <- ts.tree.collect { case dd: DefDef => dd }
      rhs <- defDef.rhs
    } f(rhs)

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
