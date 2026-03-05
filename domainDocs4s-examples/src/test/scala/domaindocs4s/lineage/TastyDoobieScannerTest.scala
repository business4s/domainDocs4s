package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.example.UserRepo
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyDoobieScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  private val doobieIntegrations = new TastyDoobieScanner().scan(List(pkg))
  private val grpcIntegrations = new TastyFs2GrpcScanner().scan(List(pkg))

  private val enrichment = IntegrationGroupConfig.builder
    .group[UserRepo]("user-db")
    .build
  private val integrations = enrichment.enrich(doobieIntegrations ++ grpcIntegrations)

  "TastyDoobieScanner" - {

    "outputs DiscoveredIntegration with classA.methodB reads/writes tableC" in {
      doobieIntegrations should not be empty
      doobieIntegrations.foreach { di =>
        di.resourceType shouldBe ResourceType.Database
        di.scanner shouldBe "doobie"
        di.method.className should not be empty
        di.method.methodName should not be empty
        di.target should not be "unknown"
      }
    }

    "detects doobie integrations in UserRepo" in {
      doobieIntegrations.foreach(_.scanner shouldBe "doobie")

      val tables = doobieIntegrations.map(_.target).toSet
      tables should contain("users")
      tables should contain("transactions")
    }

    "classifies reads and writes correctly" in {
      val reads = doobieIntegrations.filter(_.accessType == DataAccessType.Read)
      val writes = doobieIntegrations.filter(_.accessType == DataAccessType.Write)

      reads.map(_.method.methodName).toSet should contain allOf ("getBalance", "getTransactions")
      writes.map(_.method.methodName).toSet should contain allOf ("insertTransaction", "updateBalance")
    }

    "enriched doobie integrations have group user-db" in {
      val enrichedDoobie = integrations.filter(_.scanner == "doobie")
      enrichedDoobie should not be empty
      enrichedDoobie.foreach(_.group shouldBe Some("user-db"))
    }
  }

}
