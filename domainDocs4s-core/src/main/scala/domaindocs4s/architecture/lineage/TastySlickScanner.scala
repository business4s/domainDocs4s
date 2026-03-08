package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Types.*

import scala.collection.mutable.ListBuffer

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
//   2. Main scan: use SymbolUsageFinder.enumerateMethodBodies() to walk all
//      method bodies (including anonymous classes uniformly), detect
//      read/write operations, resolve table names.
//
// Lifted embedding detection:
//   .result / .headOption after .result           → Read
//   .insertOrUpdate / .++= / .delete              → Write
//
// Plain SQL detection:
//   sql"...".as[T]                                → Read
//   sqlu"..."                                     → Write
// ============================================================================

class TastySlickScanner(
    dbioTypeNames: Set[String] = Set("DBIO", "DBIOAction"),
    tableBaseTypeName: String = "Table",
)(using ctx: Context) extends IntegrationScanner {

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

    // Phase 2: use enumerateMethodBodies for uniform method enumeration
    // (handles both top-level and anonymous class methods)
    //> Can we somehow get rid of thie enumerate? Maybe similarly to what I suggested for doobie?
    val methods = SymbolUsageFinder.enumerateMethodBodies(List(packageName))
    methods.flatMap { mb =>
      // Top-level methods: filter by DBIO return type
      // Anonymous class methods: scan all (they often return Future wrapping DBIO)
      if (mb.declaredType.exists(returnsDBIO) || mb.declaredType.isEmpty) {
        findSlickOps(mb.ref, mb.rhs, fieldToTableName)
      } else Nil
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

  private def collectAnonClassTableMappings(tree: Tree, result: scala.collection.mutable.Map[String, String]): Unit = tree match {
    case Block(stats, expr) =>
      val items = stats :+ expr
      collectTableMappingsFromScope(items, result)
      items.foreach(collectAnonClassTableMappings(_, result))
    case classDef: ClassDef =>
      collectTableMappingsFromScope(classDef.rhs.body, result)
    case _ =>
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

  // ── Return type matching ─────────────────────────────────────────────────

  private def returnsDBIO(tpe: TypeOrMethodic): Boolean = tpe match {
    case mt: MethodType  => returnsDBIO(mt.resultType)
    case pt: PolyType    => returnsDBIO(pt.resultType)
    case at: AppliedType => TastyUtils.extractTypeRef(at).exists(tr => dbioTypeNames.contains(tr.name.toString))
    case tr: TypeRef     => dbioTypeNames.contains(tr.name.toString)
    case _               => false
  }

  // ── Main scan: detect read/write operations ──────────────────────────────

  private def findSlickOps(method: MethodRef, tree: Tree, fieldToTable: Map[String, String]): List[DiscoveredIntegration] = {
    val out = ListBuffer.empty[DiscoveredIntegration]
    walk(tree, out, method, fieldToTable)
    out.toList
  }

  private val readTerminals = Set("result")
  private val writeTerminals = Set("insertOrUpdate", "insertOrUpdateAll", "++=", "delete", "update", "forceInsert", "forceInsertAll", "+=")

  private def walk(tree: Tree, out: ListBuffer[DiscoveredIntegration], method: MethodRef, fieldToTable: Map[String, String]): Unit = tree match {

    // ── Plain SQL: sql"...".as[T](getResult) → Read
    case Apply(TypeApply(Select(Apply(Select(interp, sqlName), _), asName), _), _)
      if nm(sqlName, "sql") && nm(asName, "as") =>
      SqlUtils.sqlFrom(interp).foreach { sql =>
        out += mkIntegration(method, DataAccessType.Read, SqlUtils.extractTableName(sql), sql)
      }

    // ── Plain SQL: sql"...".as[T](getResult) without extra Apply
    case TypeApply(Select(Apply(Select(interp, sqlName), _), asName), _)
      if nm(sqlName, "sql") && nm(asName, "as") =>
      SqlUtils.sqlFrom(interp).foreach { sql =>
        out += mkIntegration(method, DataAccessType.Read, SqlUtils.extractTableName(sql), sql)
      }

    // ── Plain SQL: sqlu"..." → Write
    case Apply(Select(interp, sqluName), _) if nm(sqluName, "sqlu") =>
      SqlUtils.sqlFrom(interp).foreach { sql =>
        out += mkIntegration(method, DataAccessType.Write, SqlUtils.extractTableName(sql), sql)
      }

    // ── Lifted embedding: .result (possibly chained with .headOption etc) → Read
    case Select(inner, terminal) if nm(terminal, "headOption") || nm(terminal, "head") =>
      inner match {
        case Select(qual, r) if isReadTerminal(r) =>
          resolveTableName(qual, fieldToTable).foreach { table =>
            out += mkIntegration(method, DataAccessType.Read, table, s"$table.result")
          }
        case _ =>
          walk(inner, out, method, fieldToTable)
      }

    case Select(qual, terminal) if isReadTerminal(terminal) =>
      resolveTableName(qual, fieldToTable).foreach { table =>
        out += mkIntegration(method, DataAccessType.Read, table, s"$table.result")
      }

    // ── Lifted embedding: write operations
    case Apply(Select(qual, terminal), _) if isWriteTerminal(terminal) =>
      resolveTableName(qual, fieldToTable).foreach { table =>
        out += mkIntegration(method, DataAccessType.Write, table, s"$table.${TastyUtils.simpleName(terminal)}")
      }

    case Select(qual, terminal) if isWriteTerminal(terminal) =>
      resolveTableName(qual, fieldToTable).foreach { table =>
        out += mkIntegration(method, DataAccessType.Write, table, s"$table.${TastyUtils.simpleName(terminal)}")
      }

    // ── Recurse
    case Apply(fun, args)    => walk(fun, out, method, fieldToTable); args.foreach(walk(_, out, method, fieldToTable))
    case TypeApply(fun, _)   => walk(fun, out, method, fieldToTable)
    case Block(stats, expr)  => stats.foreach(walk(_, out, method, fieldToTable)); walk(expr, out, method, fieldToTable)
    case Select(qual, _)     => walk(qual, out, method, fieldToTable)
    case t: ValDef           => t.rhs.foreach(walk(_, out, method, fieldToTable))
    case t: DefDef           => t.rhs.foreach(walk(_, out, method, fieldToTable))
    case l: Lambda           => walk(l.meth, out, method, fieldToTable)
    case Inlined(body, _, _) => walk(body, out, method, fieldToTable)
    case If(_, thenp, elsep) => walk(thenp, out, method, fieldToTable); walk(elsep, out, method, fieldToTable)
    case Match(_, cases)     => cases.foreach(c => walk(c.body, out, method, fieldToTable))
    case Try(body, catches, finalizer) =>
      walk(body, out, method, fieldToTable)
      catches.foreach(c => walk(c.body, out, method, fieldToTable))
      finalizer.foreach(walk(_, out, method, fieldToTable))
    case _                   => ()
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

  private def isReadTerminal(name: tastyquery.Names.Name): Boolean =
    readTerminals.contains(TastyUtils.simpleName(name))

  private def isWriteTerminal(name: tastyquery.Names.Name): Boolean =
    writeTerminals.contains(TastyUtils.simpleName(name))

  private def nm(name: tastyquery.Names.Name, target: String): Boolean =
    TastyUtils.matchesName(name, target)

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
