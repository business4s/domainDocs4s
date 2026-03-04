package domaindocs4s.architecture.lineage

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

// ============================================================================
// Flyway Migration Scanner
//
// Discovers schema objects (tables, views) from SQL migration files.
// Does not require TASTy — reads SQL files directly from disk.
//
// Supported DDL:
//   CREATE TABLE [schema.]name          → Write
//   CREATE VIEW [schema.]name AS ...    → Write to view, Read from source tables
//   ALTER TABLE [schema.]name           → Write
//
// Migration files must follow Flyway naming: V<version>__<description>.sql
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
    val content  = new String(Files.readAllBytes(file))
    val stmts    = content.split(";").map(_.trim).filter(_.nonEmpty)

    stmts.flatMap(stmt => parseStatement(stmt, version, filename)).toList
  }

  private def parseStatement(stmt: String, version: String, filename: String): List[DiscoveredIntegration] = {
    val method = MethodRef("flyway", version)

    CreateViewPattern.findFirstMatchIn(stmt) match {
      case Some(m) =>
        val viewName = m.group(1)
        val body     = m.group(2)
        val sourceTables = SourceTablePattern.findAllMatchIn(body).map(_.group(1)).toList.distinct

        mkIntegration(method, DataAccessType.Write, viewName, filename) ::
          sourceTables.map(table => mkIntegration(method, DataAccessType.Read, table, filename))

      case None =>
        val createTable = CreateTablePattern.findFirstMatchIn(stmt).map(m =>
          mkIntegration(method, DataAccessType.Write, m.group(1), filename))

        val alterTable = AlterTablePattern.findFirstMatchIn(stmt).map(m =>
          mkIntegration(method, DataAccessType.Write, m.group(1), filename))

        // Prefer CREATE TABLE match; fall back to ALTER TABLE
        createTable.orElse(alterTable).toList
    }
  }

  private def mkIntegration(method: MethodRef, access: DataAccessType, target: String, filename: String): DiscoveredIntegration =
    DiscoveredIntegration(
      method = method,
      accessType = access,
      resourceType = "database",
      scanner = "flyway",
      target = target,
      evidence = filename,
      group = group,
    )
}

object FlywayMigrationScanner {
  private val MigrationFilePattern = """V[\d._]+__.*\.sql""".r
  private val CreateTablePattern   = """(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:\w+\.)?(\w+)""".r
  private val CreateViewPattern    = """(?is)CREATE\s+(?:OR\s+REPLACE\s+)?VIEW\s+(?:\w+\.)?(\w+)\s+AS\s+(.+)""".r
  private val AlterTablePattern    = """(?i)ALTER\s+TABLE\s+(?:IF\s+EXISTS\s+)?(?:\w+\.)?(\w+)""".r
  private val SourceTablePattern   = """(?i)(?:FROM|JOIN)\s+(?:\w+\.)?(\w+)""".r

  def apply(dir: String, group: Option[String] = None): FlywayMigrationScanner =
    new FlywayMigrationScanner(Path.of(dir), group)
}
