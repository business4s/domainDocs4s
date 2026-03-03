package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyLineageScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  // Phase 0: extract call graph
  private val callGraph = new TastyCallGraphExtractor().extract(pkg)

  // Phase 1: scan doobie integrations
  private val integrations = new TastyDoobieScanner().scan(pkg)

  // Phase 2: build lineage
  private val result = LineageBuilder.build(callGraph, integrations)

  "TastyDoobieScanner" - {

    "outputs DiscoveredIntegration with classA.methodB reads/writes tableC" in {
      integrations should not be empty
      integrations.foreach { di =>
        di.integrationType shouldBe "doobie"
        di.method.className should not be empty
        di.method.methodName should not be empty
        di.target should not be "unknown"
      }
    }

    "detects doobie integrations in UserRepo" in {
      integrations.foreach(_.integrationType shouldBe "doobie")

      val tables = integrations.map(_.target).toSet
      tables should contain("users")
      tables should contain("transactions")
    }

    "classifies reads and writes correctly" in {
      val reads = integrations.filter(_.accessType == DataAccessType.Read)
      val writes = integrations.filter(_.accessType == DataAccessType.Write)

      reads.map(_.method.methodName).toSet should contain allOf ("getBalance", "getTransactions")
      writes.map(_.method.methodName).toSet should contain allOf ("insertTransaction", "updateBalance")
    }
  }

  "TastyCallGraphExtractor" - {

    "discovers all three classes" in {
      val classNames = callGraph.map(_.className).distinct
      classNames should contain("UserRepo")
      classNames should contain("UserService")
      classNames should contain("UserGrpcApi")
    }

    "extracts call graph from UserService to UserRepo" in {
      val serviceCalls = result.callGraph.filter(_.caller.className == "UserService")
      val calledMethods = serviceCalls.map(e => (e.callee.className, e.callee.methodName))

      calledMethods should contain(("UserRepo", "getBalance"))
      calledMethods should contain(("UserRepo", "getTransactions"))
      calledMethods should contain(("UserRepo", "updateBalance"))
      calledMethods should contain(("UserRepo", "insertTransaction"))
    }

    "extracts call graph from UserGrpcApi to UserService" in {
      val apiCalls = result.callGraph.filter(_.caller.className == "UserGrpcApi")
      val calledMethods = apiCalls.map(e => (e.callee.className, e.callee.methodName))

      calledMethods should contain(("UserService", "getBalance"))
      calledMethods should contain(("UserService", "deposit"))
      calledMethods should contain(("UserService", "getHistory"))
    }
  }

  "LineageBuilder" - {

    "propagates effective access types" in {
      val apiGetBalance = result.findMethod(MethodRef("UserGrpcApi", "getBalance"))
      apiGetBalance.map(_.effectiveAccess) shouldBe Some(DataAccessType.Read)

      val apiDeposit = result.findMethod(MethodRef("UserGrpcApi", "deposit"))
      apiDeposit.map(_.effectiveAccess) shouldBe Some(DataAccessType.Write)
    }

    "builds lineage chains from API to DB" in {
      result.lineageChains should not be empty

      // getBalance chain: UserGrpcApi -> UserService -> UserRepo
      val balanceChains = result.lineageFrom(MethodRef("UserGrpcApi", "getBalance"))
      balanceChains should have size 1
      balanceChains.head.integration.target shouldBe "users"
      balanceChains.head.integration.accessType shouldBe DataAccessType.Read
      balanceChains.head.path.map(_.className) shouldBe List("UserGrpcApi", "UserService", "UserRepo")

      // deposit chain: UserGrpcApi -> UserService -> UserRepo (two chains: users + transactions)
      val depositChains = result.lineageFrom(MethodRef("UserGrpcApi", "deposit"))
      depositChains should have size 2
      depositChains.map(_.integration.target).toSet shouldBe Set("users", "transactions")
    }

    "pretty prints the full result" in {
      val output = result.prettyPrint
      println(output)
      output should include("UserRepo")
      output should include("UserService")
      output should include("UserGrpcApi")
      output should include("doobie")
    }
  }
}
