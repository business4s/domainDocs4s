package domaindocs4s.architecture.lineage

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.alter.{Alter, AlterOperation, RenameTableStatement}
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
import net.sf.jsqlparser.statement.drop.Drop
import net.sf.jsqlparser.statement.select.{FromItem, PlainSelect, Select, SetOperationList}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
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
    group: Option[String] = None,
) extends ResourceScanner {

  import FlywayMigrationScanner.*

  private lazy val parsedEvents: List[MigrationEvent] = {
    val files = Using(Files.list(migrationDir)) { stream =>
      stream.iterator().asScala.toList
        .filter(p => MigrationFilePattern.matches(p.getFileName.toString))
        .sortBy(_.getFileName.toString)
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

    stmt match {
      case ct: CreateTable =>
        List(IntegrationEvent(mkIntegration(method, DataAccessType.Write, ct.getTable.getName, filename)))

      case cv: CreateView =>
        val viewName = cv.getView.getName
        val sources = Option(cv.getSelect).toList.flatMap(extractSourceTables).distinct
        IntegrationEvent(mkIntegration(method, DataAccessType.Write, viewName, filename)) ::
          sources.map(t => IntegrationEvent(mkIntegration(method, DataAccessType.Read, t, filename))) :::
          sources.map(t => DependencyEvent(ResourceDependency(from = t, to = viewName, resourceType = ResourceType.Database, label = "view source")))

      case alt: Alter =>
        val oldName = alt.getTable.getName
        val renameExpr = Option(alt.getAlterExpressions).map(_.asScala).getOrElse(Nil)
          .find(_.getOperation == AlterOperation.RENAME_TABLE)
        renameExpr match {
          case Some(expr) => renameEvents(oldName, expr.getNewTableName, method, filename)
          case None       => List(IntegrationEvent(mkIntegration(method, DataAccessType.Write, oldName, filename)))
        }

      case rename: RenameTableStatement =>
        rename.getTableNames.asScala.toList.flatMap { entry =>
          renameEvents(entry.getKey.getName, entry.getValue.getName, method, filename)
        }

      case drop: Drop if drop.getType.equalsIgnoreCase("TABLE") || drop.getType.equalsIgnoreCase("VIEW") =>
        List(DropEvent(drop.getName.getName))

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

  private def mkIntegration(
      method: MethodRef,
      access: DataAccessType,
      target: String,
      filename: String,
  ): DiscoveredIntegration =
    DiscoveredIntegration(
      method = method,
      accessType = access,
      resourceType = ResourceType.Database,
      scanner = "flyway",
      target = target,
      evidence = filename,
      group = group,
    )
}

object FlywayMigrationScanner {
  private sealed trait MigrationEvent
  private case class IntegrationEvent(di: DiscoveredIntegration) extends MigrationEvent
  private case class DropEvent(target: String)                   extends MigrationEvent
  private case class DependencyEvent(dep: ResourceDependency)    extends MigrationEvent

  private val MigrationFilePattern = """[VR][\d._]*__.*\.sql""".r

  /** SQL identifiers must start with a letter or underscore.
    * Filters out JSqlParser artifacts from dollar-quoting (DO $$...$$)
    * and format strings (%1$s) in PL/pgSQL blocks.
    */
  private def isValidIdentifier(name: String): Boolean =
    name.nonEmpty && (name.head.isLetter || name.head == '_')

  /** Process migration events in order, removing integrations and dependencies for dropped targets.
    *
    * When a DropEvent(X) is encountered, all accumulated integrations with target X
    * are removed, and all dependencies where `to == X` are removed.
    * If a subsequent migration re-creates X, fresh integrations/dependencies are added.
    */
  private def resolveDrops(events: List[MigrationEvent]): (List[DiscoveredIntegration], List[ResourceDependency]) = {
    val integrations = scala.collection.mutable.ListBuffer.empty[DiscoveredIntegration]
    val dependencies = scala.collection.mutable.ListBuffer.empty[ResourceDependency]
    for (event <- events) event match {
      case IntegrationEvent(di)  => integrations += di
      case DependencyEvent(dep)  => dependencies += dep
      case DropEvent(target)     =>
        integrations.filterInPlace(_.target != target)
        dependencies.filterInPlace(_.to != target)
    }
    (integrations.toList, dependencies.toList)
  }

  /** Parse SQL content into statements, silently skipping unparseable ones
    * (e.g. PL/pgSQL procedural blocks with DO $$...$$ or CREATE PROCEDURE).
    */
  private def parseStatements(content: String): List[net.sf.jsqlparser.statement.Statement] = {
    Try {
      CCJSqlParserUtil.parseStatements(content).asScala.toList
    }.getOrElse {
      // If batch parse fails (e.g. PL/pgSQL blocks), try individual statements
      content.split(";").toList.flatMap { s =>
        val trimmed = s.trim
        if (trimmed.isEmpty) None
        else Try(CCJSqlParserUtil.parse(trimmed)).toOption
      }
    }
  }

  /** Extract table names from a SELECT's FROM and JOIN clauses. */
  private def extractSourceTables(select: Select): List[String] = select match {
    case ps: PlainSelect =>
      val from = Option(ps.getFromItem).toList.flatMap(tableName)
      val joins = Option(ps.getJoins).map(_.asScala.toList).getOrElse(Nil)
        .flatMap(j => Option(j.getFromItem)).flatMap(tableName)
      from ++ joins
    case sol: SetOperationList =>
      Option(sol.getSelects).map(_.asScala.toList).getOrElse(Nil).flatMap {
        case s: Select => extractSourceTables(s)
        case _         => Nil
      }
    case _ => Nil
  }

  private def tableName(fi: FromItem): Option[String] = fi match {
    case t: Table => Some(t.getName)
    case _        => None
  }

  def apply(dir: String, group: Option[String] = None): FlywayMigrationScanner =
    new FlywayMigrationScanner(Path.of(dir), group)
}
