package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

import java.nio.file.Paths

class FlywayMigrationScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  private val flywayDir = Paths.get(getClass.getClassLoader.getResource("flyway").toURI)
  private val flywayScanner = new FlywayMigrationScanner(flywayDir, group = Some("core-db"))
  private val flywayIntegrations = flywayScanner.scan()

  "FlywayMigrationScanner" - {

    "discovers CREATE TABLE statements as Write" in {
      val creates = flywayIntegrations.filter { di =>
        di.accessType == DataAccessType.Write && di.evidence == "V001__create_tables.sql"
      }
      creates.map(_.target).toSet should contain allOf ("users", "transactions")
    }

    "discovers ALTER TABLE as Write" in {
      val alters = flywayIntegrations.filter(_.evidence == "V003__alter_tables.sql")
      alters should have size 1
      alters.head.target shouldBe "users"
      alters.head.accessType shouldBe DataAccessType.Write
    }

    "discovers CREATE VIEW as Write to view and Read from source tables (after drop+recreate)" in {
      // V002 creates the view, V004 drops it, V005 recreates it
      val viewWrites = flywayIntegrations.filter(di =>
        di.accessType == DataAccessType.Write && di.target == "user_transaction_summary"
      )
      viewWrites should have size 1
      viewWrites.head.evidence shouldBe "V005__recreate_view.sql"

      val sourceReads = flywayIntegrations.filter(di =>
        di.accessType == DataAccessType.Read && di.evidence == "V005__recreate_view.sql"
      )
      sourceReads.map(_.target).toSet should contain allOf ("users", "transactions")
    }

    "DROP VIEW removes view Write but preserves source table reads from that migration" in {
      // V002 created view reading users+transactions. V004 drops the view.
      // Write to user_transaction_summary from V002 is gone,
      // but Read from users/transactions in V002 survives (different target).
      val v002Integrations = flywayIntegrations.filter(_.evidence == "V002__create_views.sql")
      v002Integrations.foreach(_.accessType shouldBe DataAccessType.Read)
      v002Integrations.map(_.target).toSet should contain allOf ("users", "transactions")
    }

    "DROP does not affect unrelated tables" in {
      // V004 drops user_transaction_summary, but users and transactions tables survive
      val userWrites = flywayIntegrations.filter(di =>
        di.accessType == DataAccessType.Write && di.target == "users"
      )
      userWrites should not be empty

      val txWrites = flywayIntegrations.filter(di =>
        di.accessType == DataAccessType.Write && di.target == "transactions"
      )
      txWrites should not be empty
    }

    "RENAME removes old table name and adds new one" in {
      // V006 creates audit_log, V007 renames it to activity_log
      flywayIntegrations.filter(_.target == "audit_log") shouldBe empty

      val renamed = flywayIntegrations.filter(_.target == "activity_log")
      renamed should have size 1
      renamed.head.accessType shouldBe DataAccessType.Write
      renamed.head.evidence shouldBe "V007__rename_table.sql"
    }

    "RENAME preserves unrelated tables" in {
      flywayIntegrations.filter(di =>
        di.accessType == DataAccessType.Write && di.target == "users"
      ) should not be empty

      flywayIntegrations.filter(di =>
        di.accessType == DataAccessType.Write && di.target == "transactions"
      ) should not be empty
    }

    "scanDependencies returns VIEW source table relationships" in {
      val deps = flywayScanner.scanDependencies()
      // V005 recreates the view (V002 created, V004 dropped)
      val viewDeps = deps.filter(_.to == "user_transaction_summary")
      viewDeps.map(_.from).toSet should contain allOf ("users", "transactions")
      viewDeps.foreach { dep =>
        dep.resourceType shouldBe ResourceType.Database
        dep.label shouldBe "view source"
      }
    }

    "DROP VIEW removes dependencies for that view" in {
      // V002 creates view with deps, V004 drops it → V002 deps gone
      // V005 recreates → fresh deps survive
      val deps = flywayScanner.scanDependencies()
      val viewDeps = deps.filter(_.to == "user_transaction_summary")
      // Only 2 deps (from V005 recreate), not 4 (V002 + V005)
      viewDeps should have size 2
    }

    "all flyway integrations have resourceType database and scanner flyway" in {
      flywayIntegrations should not be empty
      flywayIntegrations.foreach { di =>
        di.resourceType shouldBe ResourceType.Database
        di.scanner shouldBe "flyway"
      }
    }

    "all flyway integrations have group core-db" in {
      flywayIntegrations.foreach(_.group shouldBe Some("core-db"))
    }

    "evidence includes filename" in {
      flywayIntegrations.foreach { di =>
        di.evidence should endWith(".sql")
      }
    }

    "method refs use flyway class and version" in {
      flywayIntegrations.foreach { di =>
        di.method.className shouldBe "flyway"
        di.method.methodName should startWith("V")
      }
    }

    "merges with doobie integrations into resources" in {
      val doobieIntegrations = new TastyDoobieScanner().scan(List(pkg))
      val allIntegrations = doobieIntegrations ++ flywayIntegrations
      val resources = DiscoveredResource.merge(allIntegrations)

      // "users" table from doobie (group=None) and flyway (group=Some("core-db")) stay separate
      // because group differs
      val usersResources = resources.filter(_.target == "users")
      usersResources should not be empty

      // But if groups matched, they would merge
      val ungroupedFlyway = new FlywayMigrationScanner(flywayDir).scan()
      val doobieAndFlyway = doobieIntegrations ++ ungroupedFlyway
      val merged = DiscoveredResource.merge(doobieAndFlyway)
      val usersResource = merged.filter(r => r.target == "users" && r.group.isEmpty)
      usersResource should have size 1
      usersResource.head.discoveries.map(_.scanner).toSet should contain allOf ("doobie", "flyway")
    }

    "resource scanners contribute to resources but not to integrations" in {
      val scanner = LineageScanner(
        packages = List(pkg),
        scanners = List(new TastyDoobieScanner()),
        resourceScanners = List(new FlywayMigrationScanner(flywayDir, group = Some("core-db"))),
      )
      val result = scanner.scan()

      // Flyway results should not appear in integrations (no "flyway" class node or edges)
      result.integrations.filter(_.scanner == "flyway") shouldBe empty

      // Flyway results should be in resourceOnlyIntegrations
      result.resourceOnlyIntegrations.filter(_.scanner == "flyway") should not be empty

      // Resources should include both doobie and flyway tables
      val scanners = result.resources.flatMap(_.discoveries.map(_.scanner)).toSet
      scanners should contain("doobie")
      scanners should contain("flyway")
    }
  }

}
