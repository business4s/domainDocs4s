package domaindocs4s.architecture.lineage

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.alter.{Alter, AlterOperation, RenameTableStatement}
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.drop.Drop
import net.sf.jsqlparser.statement.select.{FromItem, ParenthesedSelect, PlainSelect, Select, SetOperationList}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.seqOrdering
import scala.util.{Try, Using}

// ============================================================================
// Flyway Migration Scanner
//
// Discovers schema objects (tables, views) from SQL migration files.
// Does not require TASTy — reads SQL files directly from disk.
//
// Uses JSqlParser for robust SQL parsing. Handles:
//   CREATE TABLE [schema.]name          → Write
//   CREATE VIEW [schema.]name AS ...    → Write to view, Read from source tables
//   ALTER TABLE [schema.]name           → Write
//   ALTER TABLE [schema.]name RENAME TO → Drop old + Write new
//   RENAME TABLE old TO new             → Drop old + Write new (MySQL syntax)
//   DROP TABLE [schema.]name            → removes previously discovered integrations for name
//   DROP VIEW [schema.]name             → removes previously discovered integrations for name
//
// PL/pgSQL procedural blocks (DO $$...$$, CREATE PROCEDURE) are silently
// skipped since they typically contain dynamic partition management, not
// schema definitions relevant for architecture diagrams.
//
// Migration files must follow Flyway naming: V<version>__<description>.sql
//   or repeatable migrations: R__<description>.sql
// ============================================================================

class FlywayMigrationScanner(
    migrationDir: Path,
    database: Option[String] = None,
    schema: Option[String] = None,
    cluster: Option[String] = None,
) extends ResourceScanner {

  import FlywayMigrationScanner.*

  private lazy val parsedEvents: List[MigrationEvent] = {
    val files = Using(Files.list(migrationDir)) { stream =>
      stream
        .iterator()
        .asScala
        .toList
        .filter(p => MigrationFilePattern.matches(p.getFileName.toString))
        .sortBy(p => flywayVersionKey(p.getFileName.toString))
    }.getOrElse(Nil)

    files.flatMap(parseMigrationFile).filter {
      case IntegrationEvent(di) => isValidIdentifier(di.target)
      case _                    => true
    }
  }

  private lazy val resolved = resolveDrops(parsedEvents)

  def scan(): List[DiscoveredIntegration] = resolved._1

  override def scanDependencies(): List[ResourceDependency] = resolved._2

  private def parseMigrationFile(file: Path): List[MigrationEvent] = {
    val filename = file.getFileName.toString
    val version  = filename.takeWhile(_ != '_')
    val content  = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
    val stmts    = parseStatements(content)

    stmts.flatMap(stmt => processStatement(stmt, version, filename))
  }

  private def processStatement(
      stmt: net.sf.jsqlparser.statement.Statement,
      version: String,
      filename: String,
  ): List[MigrationEvent] = {
    val method = MethodRef("", "flyway", version)

    def evidence(sql: String) = s"$filename: $sql"

    stmt match {
      case ct: CreateTable =>
        List(IntegrationEvent(mkIntegration(method, DataAccessType.Write, ct.getTable.getName, evidence(s"CREATE TABLE ${ct.getTable.getName}"))))

      case cv: CreateView =>
        val viewName = cv.getView.getName
        val sources  = Option(cv.getSelect).toList.flatMap(extractSourceTables).distinct
        val ev       = evidence(s"CREATE VIEW $viewName AS SELECT FROM ${sources.mkString(", ")}")
        IntegrationEvent(mkIntegration(method, DataAccessType.Write, viewName, ev)) ::
          sources.map(t => IntegrationEvent(mkIntegration(method, DataAccessType.Read, t, ev))) :::
          sources.map(t => DependencyEvent(ResourceDependency(from = mkResourceId(t), to = mkResourceId(viewName), label = "view source")))

      case alt: Alter =>
        val oldName    = alt.getTable.getName
        val renameExpr = Option(alt.getAlterExpressions)
          .map(_.asScala)
          .getOrElse(Nil)
          .find(_.getOperation == AlterOperation.RENAME_TABLE)
        renameExpr match {
          case Some(expr) => renameEvents(oldName, expr.getNewTableName, method, filename)
          case None       => List(IntegrationEvent(mkIntegration(method, DataAccessType.Write, oldName, evidence(s"ALTER TABLE $oldName"))))
        }

      case rename: RenameTableStatement =>
        rename.getTableNames.asScala.toList.flatMap { entry =>
          renameEvents(entry.getKey.getName, entry.getValue.getName, method, filename)
        }

      case drop: Drop if drop.getType.equalsIgnoreCase("TABLE") || drop.getType.equalsIgnoreCase("VIEW") =>
        val cascade = Option(drop.getParameters).exists(_.asScala.exists(_.toString.equalsIgnoreCase("CASCADE")))
        List(DropEvent(drop.getName.getName, cascade))

      case _ => Nil
    }
  }

  private def renameEvents(
      oldName: String,
      newName: String,
      method: MethodRef,
      filename: String,
  ): List[MigrationEvent] =
    List(DropEvent(oldName), IntegrationEvent(mkIntegration(method, DataAccessType.Write, newName, filename)))

  private def mkResourceId(table: String): ResourceId =
    ResourceId.DbTable(table = table, database = database, schema = schema, cluster = cluster)

  private def mkIntegration(
      method: MethodRef,
      access: DataAccessType,
      target: String,
      evidence: String,
  ): DiscoveredIntegration =
    DiscoveredIntegration(
      method = method,
      accessType = access,
      resourceId = mkResourceId(target),
      scanner = "flyway",
      evidence = evidence,
    )
}

object FlywayMigrationScanner {
  sealed private trait MigrationEvent
  private case class IntegrationEvent(di: DiscoveredIntegration) extends MigrationEvent
  private case class DropEvent(target: String, cascade: Boolean = false) extends MigrationEvent
  private case class DependencyEvent(dep: ResourceDependency)    extends MigrationEvent

  private val MigrationFilePattern = """[VR][\d._]*__.*\.sql""".r

  /** Sort key for Flyway migration filenames.
    *
    * Flyway orders migrations by version number (numeric segments), not lexicographically. `V1_0_7` < `V1_0_11`, but lexicographic sort puts
    * `V1_0_11` before `V1_0_7`. Repeatable migrations (R__*) sort after all versioned migrations.
    */
  private def flywayVersionKey(filename: String): (Int, List[Long], String) =
    if (filename.startsWith("V")) {
      val versionStr = filename.drop(1).takeWhile(c => c.isDigit || c == '_' || c == '.')
      val segments   = versionStr.split("[_.]").toList.flatMap(s => scala.util.Try(s.toLong).toOption)
      (0, segments, filename)
    } else {
      // Repeatable migrations (R__*) sort after versioned ones
      (1, Nil, filename)
    }

  /** SQL identifiers must start with a letter or underscore. Filters out JSqlParser artifacts from dollar-quoting (DO $$...$$) and format strings
    * (%1$s) in PL/pgSQL blocks.
    */
  private def isValidIdentifier(name: String): Boolean =
    name.nonEmpty && (name.head.isLetter || name.head == '_')

  /** Process migration events in order, removing integrations and dependencies for dropped targets.
    *
    * When a DropEvent(X) is encountered, all accumulated integrations with target X are removed, and all dependencies where `to == X` are removed. If
    * a subsequent migration re-creates X, fresh integrations/dependencies are added.
    */
  private def resolveDrops(events: List[MigrationEvent]): (List[DiscoveredIntegration], List[ResourceDependency]) = {
    val integrations = scala.collection.mutable.ListBuffer.empty[DiscoveredIntegration]
    val dependencies = scala.collection.mutable.ListBuffer.empty[ResourceDependency]

    def dropTarget(name: String, cascade: Boolean): Unit = {
      // Collect dependents before removing edges (so we know what to cascade to)
      val dependents = if (cascade) dependencies.collect { case d if d.from.label == name => d.to.label }.distinct.toList else Nil
      // Remove edges first to break any potential cycles before recursing
      integrations.filterInPlace(_.target != name)
      dependencies.filterInPlace(d => d.to.label != name && d.from.label != name)
      dependents.foreach(dep => dropTarget(dep, cascade))
    }

    for (event <- events) event match {
      case IntegrationEvent(di)          => integrations += di
      case DependencyEvent(dep)          => dependencies += dep
      case DropEvent(target, cascade)    => dropTarget(target, cascade)
    }
    (integrations.toList, dependencies.toList)
  }

  /** Parse SQL content into statements, silently skipping unparseable ones (e.g. PL/pgSQL procedural blocks with DO $$...$$ or CREATE PROCEDURE).
    */
  private def parseStatements(content: String): List[net.sf.jsqlparser.statement.Statement] = {
    val preprocessed = stripUnsupportedClauses(content)
    Try {
      CCJSqlParserUtil.parseStatements(preprocessed).asScala.toList
    }.getOrElse {
      // If batch parse fails (e.g. PL/pgSQL blocks), try individual statements
      preprocessed.split(";").toList.flatMap { s =>
        val trimmed = s.trim
        if (trimmed.isEmpty) None
        else Try(CCJSqlParserUtil.parse(trimmed)).toOption
      }
    }
  }

  /** Extract real table names from a SELECT, flattening CTEs.
    *
    * CTE names (WITH x AS (...)) are not real tables — they're intermediate aliases. This method collects CTE names, recursively extracts real tables
    * from CTE bodies, and filters CTE names out of the final result.
    */
  private def extractSourceTables(select: Select): List[String] = {
    // Collect CTE names and recursively extract their source tables
    val withItems  = Option(select.getWithItemsList).map(_.asScala.toList).getOrElse(Nil)
    val cteNames   = withItems.flatMap(wi => Option(wi.getAlias).map(_.getName)).toSet
    val cteSources = withItems.flatMap(wi => Option(wi.getSelect).toList.flatMap(extractSourceTables))

    // Extract tables from the main query body
    val mainSources = select match {
      case ps: PlainSelect       =>
        val from  = Option(ps.getFromItem).toList.flatMap(extractFromItem)
        val joins = Option(ps.getJoins)
          .map(_.asScala.toList)
          .getOrElse(Nil)
          .flatMap(j => Option(j.getFromItem))
          .flatMap(extractFromItem)
        from ++ joins
      case sol: SetOperationList =>
        Option(sol.getSelects).map(_.asScala.toList).getOrElse(Nil).flatMap {
          case s: Select => extractSourceTables(s)
          case _         => Nil
        }
      case ps: ParenthesedSelect =>
        Option(ps.getSelect).toList.flatMap(extractSourceTables)
      case _                     => Nil
    }

    // Return all sources minus CTE names
    (cteSources ++ mainSources).filterNot(cteNames.contains)
  }

  private def extractFromItem(fi: FromItem): List[String] = fi match {
    case t: Table  => List(t.getName)
    case s: Select => extractSourceTables(s)
    case _         => Nil
  }

  /** Strip DDL clauses that JSqlParser cannot parse.
    *
    * PostgreSQL `CREATE TABLE ... PARTITION BY {RANGE|LIST|HASH} (cols)` is not supported by JSqlParser (as of 5.3). We remove the clause so that the
    * CREATE TABLE itself can still be parsed and the table name discovered.
    */
  private val PartitionByPattern =
    """(?i)\bPARTITION\s+BY\s+(?:RANGE|LIST|HASH)\s*\([^)]*\)""".r

  private def stripUnsupportedClauses(sql: String): String =
    PartitionByPattern.replaceAllIn(sql, "")

  def apply(dir: String, database: Option[String] = None, schema: Option[String] = None, cluster: Option[String] = None): FlywayMigrationScanner =
    new FlywayMigrationScanner(Path.of(dir), database = database, schema = schema, cluster = cluster)
}
