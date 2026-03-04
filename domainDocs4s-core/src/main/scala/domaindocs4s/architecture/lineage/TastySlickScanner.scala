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
//   2. Main scan: find methods returning DBIO, walk bodies to detect
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
    val classes = TastyUtils.userClasses(pkg)
    val modules = TastyUtils.moduleClasses(pkg)

    // Phase 1: pre-scan — build lookup maps
    val tableClassToName = buildTableClassMap(classes)
    val fieldToTableName = buildFieldToTableMap(modules, tableClassToName)

    // Phase 2: main scan — find DBIO methods and detect operations
    (classes ++ modules).flatMap { cls =>
      val className = cls.name.toString.stripSuffix("$")
      cls.declarations.collect {
        case ts: TermSymbol if returnsDBIO(ts.declaredType) =>
          val ref = MethodRef(packageName, className, ts.name.toString)
          ts.tree.toList.flatMap {
            case defDef: DefDef => defDef.rhs.toList.flatMap(rhs => findSlickOps(ref, rhs, fieldToTableName))
            case _              => Nil
          }
      }.flatten
    }
  }

  // ── Pre-scan: Table class → table name ───────────────────────────────────

  /** Extract table name from Table subclass parent constructor: Table[T](tag, "table_name"). */
  private def buildTableClassMap(classes: List[ClassSymbol]): Map[String, String] =
    classes.flatMap { cls =>
      cls.tree.toList.flatMap { classDef =>
        classDef.rhs.parents.flatMap(extractTableNameFromParent)
          .headOption.map(cls.name.toString -> _)
      }
    }.toMap

  /** Walk parent constructor tree to find string literal arg when parent is a Table. */
  private def extractTableNameFromParent(tree: Tree): Option[String] = tree match {
    case Apply(fun, args) =>
      if (isTableInit(fun))
        args.collectFirst { case Literal(c) if c.value.isInstanceOf[String] => c.value.asInstanceOf[String] }
      else
        None
    case _ => None
  }

  /** Check if a tree is a Table constructor call (Select(New, <init>) with Table in sig). */
  private def isTableInit(tree: Tree): Boolean = tree match {
    case TypeApply(inner, _)   => isTableInit(inner)
    case Select(_, name) =>
      val s = name.toString
      s.startsWith("<init>") && s.contains(tableBaseTypeName)
    case _ => false
  }

  // ── Pre-scan: TableQuery field → table name ──────────────────────────────

  /** Build field name → table name map from module classes with TableQuery[T] fields. */
  private def buildFieldToTableMap(modules: List[ClassSymbol], tableClassToName: Map[String, String]): Map[String, String] =
    modules.flatMap { mod =>
      mod.declarations.collect {
        case ts: TermSymbol if !ts.name.toString.startsWith("<") =>
          extractTableQueryClassName(ts.declaredType).flatMap { tableClassName =>
            tableClassToName.get(tableClassName).map(ts.name.toString -> _)
          }
      }.flatten
    }.toMap

  /** Extract the Table class name from a TableQuery[T] type. */
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

  // Terminal names that indicate a Read (lifted embedding)
  private val readTerminals = Set("result")
  // Terminal names that indicate a Write (lifted embedding)
  private val writeTerminals = Set("insertOrUpdate", "insertOrUpdateAll", "++=", "delete", "update", "forceInsert", "forceInsertAll", "+=")

  private def walk(tree: Tree, out: ListBuffer[DiscoveredIntegration], method: MethodRef, fieldToTable: Map[String, String]): Unit = tree match {

    // ── Plain SQL: sql"...".as[T](getResult) → Read ──────────────────────
    // Shape: Apply(TypeApply(Select(Apply(Select(sqlInterp, sql), _), as), _), _)
    case Apply(TypeApply(Select(Apply(Select(interp, sqlName), _), asName), _), _)
      if nm(sqlName, "sql") && nm(asName, "as") =>
      SqlUtils.sqlFrom(interp).foreach { sql =>
        out += mkIntegration(method, DataAccessType.Read, SqlUtils.extractTableName(sql), sql)
      }

    // ── Plain SQL: sql"...".as[T](getResult) without extra Apply ─────────
    case TypeApply(Select(Apply(Select(interp, sqlName), _), asName), _)
      if nm(sqlName, "sql") && nm(asName, "as") =>
      SqlUtils.sqlFrom(interp).foreach { sql =>
        out += mkIntegration(method, DataAccessType.Read, SqlUtils.extractTableName(sql), sql)
      }

    // ── Plain SQL: sqlu"..." → Write ─────────────────────────────────────
    // Shape: Apply(Select(interp, sqlu), _)
    case Apply(Select(interp, sqluName), _) if nm(sqluName, "sqlu") =>
      SqlUtils.sqlFrom(interp).foreach { sql =>
        out += mkIntegration(method, DataAccessType.Write, SqlUtils.extractTableName(sql), sql)
      }

    // ── Lifted embedding: .result (possibly chained with .headOption etc) → Read
    // Shape: Select(Select(extensionMethods(...tableQuery...), result), headOption)
    //    or: Select(extensionMethods(...tableQuery...), result)
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

    // ── Lifted embedding: write operations ───────────────────────────────
    // Shape: Apply(Select(extensionMethods(...tableQuery...), insertOrUpdate), args)
    //    or: Select(extensionMethods(...tableQuery...), delete)
    case Apply(Select(qual, terminal), _) if isWriteTerminal(terminal) =>
      resolveTableName(qual, fieldToTable).foreach { table =>
        out += mkIntegration(method, DataAccessType.Write, table, s"$table.${TastyUtils.simpleName(terminal)}")
      }

    case Select(qual, terminal) if isWriteTerminal(terminal) =>
      resolveTableName(qual, fieldToTable).foreach { table =>
        out += mkIntegration(method, DataAccessType.Write, table, s"$table.${TastyUtils.simpleName(terminal)}")
      }

    // ── Recurse ──────────────────────────────────────────────────────────
    case Apply(fun, args)    => walk(fun, out, method, fieldToTable); args.foreach(walk(_, out, method, fieldToTable))
    case TypeApply(fun, _)   => walk(fun, out, method, fieldToTable)
    case Block(stats, expr)  => stats.foreach(walk(_, out, method, fieldToTable)); walk(expr, out, method, fieldToTable)
    case t: ValDef           => t.rhs.foreach(walk(_, out, method, fieldToTable))
    case _                   => ()
  }

  // ── Table name resolution ────────────────────────────────────────────────

  /** Resolve the table name from a tree by finding the first Ident that matches a known TableQuery field.
    * Short-circuits on first match to avoid building the full ident list.
    */
  private def resolveTableName(tree: Tree, fieldToTable: Map[String, String]): Option[String] =
    findFirstTableIdent(tree, fieldToTable)

  private def findFirstTableIdent(tree: Tree, fieldToTable: Map[String, String]): Option[String] = tree match {
    case Ident(name) => fieldToTable.get(TastyUtils.simpleName(name))
    case Select(qual, _) => findFirstTableIdent(qual, fieldToTable)
    case Apply(fun, args) =>
      findFirstTableIdent(fun, fieldToTable).orElse(
        args.iterator.map(findFirstTableIdent(_, fieldToTable)).collectFirst { case Some(v) => v },
      )
    case TypeApply(fun, _) => findFirstTableIdent(fun, fieldToTable)
    case Block(stats, expr) =>
      stats.iterator.map(findFirstTableIdent(_, fieldToTable)).collectFirst { case Some(v) => v }
        .orElse(findFirstTableIdent(expr, fieldToTable))
    case _ => None
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private def isReadTerminal(name: tastyquery.Names.Name): Boolean =
    readTerminals.contains(TastyUtils.simpleName(name))

  private def isWriteTerminal(name: tastyquery.Names.Name): Boolean =
    writeTerminals.contains(TastyUtils.simpleName(name))

  private def nm(name: tastyquery.Names.Name, target: String): Boolean =
    TastyUtils.matchesName(name, target)

  private def mkIntegration(method: MethodRef, access: DataAccessType, table: String, evidence: String): DiscoveredIntegration =
    DiscoveredIntegration(
      method = method,
      accessType = access,
      resourceType = "database",
      scanner = "slick",
      target = table,
      evidence = evidence,
    )
}
