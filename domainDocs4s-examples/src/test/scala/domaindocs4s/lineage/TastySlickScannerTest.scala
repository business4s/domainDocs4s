package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastySlickScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val slickPkg = "domaindocs4s.architecture.lineage.example.slick"
  private val slickIntegrations = new TastySlickScanner().scan(List(slickPkg))

  "TastySlickScanner" - {

    "outputs DiscoveredIntegration with resourceType database and scanner slick" in {
      slickIntegrations should not be empty
      slickIntegrations.foreach { di =>
        di.resourceType shouldBe ResourceType.Database
        di.scanner shouldBe "slick"
        di.method.className should not be empty
        di.method.methodName should not be empty
        di.target should not be "unknown"
      }
    }

    "detects table names from lifted embedding operations" in {
      val tables = slickIntegrations.map(_.target).toSet
      tables should contain("account_balances")
      tables should contain("slick_transactions")
    }

    "classifies reads and writes correctly" in {
      val reads = slickIntegrations.filter(_.accessType == DataAccessType.Read)
      val writes = slickIntegrations.filter(_.accessType == DataAccessType.Write)

      reads.map(_.method.methodName).toSet should contain allOf ("getBalance", "listTransactions")
      writes.map(_.method.methodName).toSet should contain allOf ("upsertBalance", "insertTransactions", "deleteTransaction")
    }

    "detects sql interpolation as Read" in {
      val sqlReads = slickIntegrations.filter { di =>
        di.method.methodName == "getBalancePlainSql" && di.accessType == DataAccessType.Read
      }
      sqlReads should have size 1
      sqlReads.head.target shouldBe "account_balances"
      sqlReads.head.evidence should include("SELECT")
    }

    "detects sqlu interpolation as Write" in {
      val sqlWrites = slickIntegrations.filter { di =>
        di.method.methodName == "updateBalancePlainSql" && di.accessType == DataAccessType.Write
      }
      sqlWrites should have size 1
      sqlWrites.head.target shouldBe "account_balances"
      sqlWrites.head.evidence should include("UPDATE")
    }

    "composes with LineageBuilder" in {
      val slickCallGraph = new TastyCallGraphExtractor().extract(slickPkg)
      val slickResult = LineageBuilder.build(slickCallGraph, slickIntegrations)

      slickResult.integrations should have size slickIntegrations.size
      val output = slickResult.prettyPrint
      println(output)
      output should include("slick")
      output should include("database")
    }

    // -- Factory pattern (anonymous class) tests --

    val factoryIntegrations = slickIntegrations.filter(_.method.className == "FactoryRepo")

    "factory pattern: discovers integrations inside anonymous class" in {
      factoryIntegrations should not be empty
    }

    "factory pattern: resolves literal table name" in {
      factoryIntegrations.map(_.target).toSet should contain("factory_orders")
    }

    "factory pattern: produces unresolved placeholder for variable table name" in {
      factoryIntegrations.map(_.target).toSet should contain("<unresolved:ItemTable>")
    }

    "factory pattern: classifies reads and writes correctly" in {
      val reads = factoryIntegrations.filter(_.accessType == DataAccessType.Read)
      val writes = factoryIntegrations.filter(_.accessType == DataAccessType.Write)

      reads.map(_.method.methodName).toSet should contain allOf ("getOrders", "getItems")
      writes.map(_.method.methodName).toSet should contain allOf ("insertOrder", "deleteItem")
    }
  }

}
