package domaindocs4s.architecture.lineage

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.alter.Alter
import net.sf.jsqlparser.statement.create.table.CreateTable
import net.sf.jsqlparser.statement.create.view.CreateView
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

  def scan(): List[DiscoveredIntegration] = {
    val files = Using(Files.list(migrationDir)) { stream =>
      stream.iterator().asScala.toList
        .filter(p => MigrationFilePattern.matches(p.getFileName.toString))
        .sortBy(_.getFileName.toString)
    }.getOrElse(Nil)

    files.flatMap(parseMigrationFile)
  }

  private def parseMigrationFile(file: Path): List[DiscoveredIntegration] = {
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
  ): List[DiscoveredIntegration] = {
    val method = MethodRef("", "flyway", version)

    stmt match {
      case ct: CreateTable =>
        List(mkIntegration(method, DataAccessType.Write, ct.getTable.getName, filename))

      case cv: CreateView =>
        val viewName = cv.getView.getName
        val sources = Option(cv.getSelect).toList.flatMap(extractSourceTables).distinct
        mkIntegration(method, DataAccessType.Write, viewName, filename) ::
          sources.map(t => mkIntegration(method, DataAccessType.Read, t, filename))

      case alt: Alter =>
        List(mkIntegration(method, DataAccessType.Write, alt.getTable.getName, filename))

      case _ => Nil
    }
  }

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
  private val MigrationFilePattern = """[VR][\d._]*__.*\.sql""".r

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
