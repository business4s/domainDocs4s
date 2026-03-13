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

    "factory pattern: resolves variable table name via val binding" in {
      factoryIntegrations.map(_.target).toSet should contain("factory_items")
    }

    "factory pattern: classifies reads and writes correctly" in {
      val reads = factoryIntegrations.filter(_.accessType == DataAccessType.Read)
      val writes = factoryIntegrations.filter(_.accessType == DataAccessType.Write)

      reads.map(_.method.methodName).toSet should contain allOf ("getOrders", "getItems")
      writes.map(_.method.methodName).toSet should contain allOf ("insertOrder", "deleteItem")
    }

    // -- SimpleDBIO s"..." detection --

    "detects s interpolation with INSERT as Write (SimpleDBIO pattern)" in {
      val hits = slickIntegrations.filter(i =>
        i.method.className == "SimpleDbioRepo" && i.method.methodName == "insertRow")
      hits should have size 1
      hits.head.accessType shouldBe DataAccessType.Write
      hits.head.target shouldBe "simple_dbio_table"
    }

    "detects s interpolation with SELECT as Read (SimpleDBIO pattern)" in {
      val hits = slickIntegrations.filter(i =>
        i.method.className == "SimpleDbioRepo" && i.method.methodName == "selectRow")
      hits should have size 1
      hits.head.accessType shouldBe DataAccessType.Read
      hits.head.target shouldBe "simple_dbio_read_table"
    }

    "ignores non-SQL s interpolation strings" in {
      val hits = slickIntegrations.filter(i =>
        i.method.className == "SimpleDbioRepo" && i.method.methodName == "nonSqlString")
      hits shouldBe empty
    }

    // -- SQL variable resolution --

    "resolves interpolation variables in sqlu via class-level val bindings" in {
      val hits = slickIntegrations.filter(i =>
        i.method.className == "SqlVarRepo" && i.method.methodName == "insertWithInterp")
      hits should have size 1
      hits.head.target shouldBe "interp_table"
    }

    // -- Val-bound table name --

    "resolves val-bound table names instead of unresolved placeholder" in {
      val hits = slickIntegrations.filter(i => i.method.className == "ValBoundTableRepo")
      hits should not be empty
      hits.head.target shouldBe "val_bound_table"
    }

    // -- Nested Table in companion object --

    "detects table operations on nested Table in companion object" in {
      val hits = slickIntegrations.filter(i => i.method.className == "NestedTableRepo")
      hits should not be empty
      hits.head.target shouldBe "nested_obj_table"
    }

    // -- InsertActionComposerImpl --

    "detects InsertActionComposerImpl pattern as Write" in {
      val hits = slickIntegrations.filter(i =>
        i.method.className == "ComposerRepo" && i.method.methodName == "upsertViaComposer")
      hits should have size 1
      hits.head.accessType shouldBe DataAccessType.Write
      hits.head.target shouldBe "account_balances"
    }

    // -- Parameterized factory: backward param propagation --
    //
    // SingleSiteRepo.apply(tableName) is called from:
    //   SingleSiteUser  → "param_single"
    //   MultiSiteUserA  → "param_multi_a"
    //   MultiSiteUserB  → "param_multi_b"
    //
    // All three literals are discovered via ParamValueIndex and each produces
    // its own DiscoveredIntegration attributed to SingleSiteRepo.

    val paramIntegrations = slickIntegrations.filter(_.method.className == "SingleSiteRepo")
    val paramTargets      = paramIntegrations.map(_.target).toSet

    "parameterized factory: resolves all call-site literals" in {
      paramIntegrations should not be empty
      paramTargets should contain("param_single")
      paramTargets should contain("param_multi_a")
      paramTargets should contain("param_multi_b")
    }

    "parameterized factory: no unresolved sentinel in output" in {
      paramTargets.filter(_.startsWith("<unresolved:")) shouldBe empty
    }

    "parameterized factory: classifies reads and writes correctly" in {
      val reads  = paramIntegrations.filter(_.accessType == DataAccessType.Read)
      val writes = paramIntegrations.filter(_.accessType == DataAccessType.Write)
      reads.map(_.target).toSet  should contain("param_single")
      writes.map(_.target).toSet should contain("param_single")
    }
  }

}
