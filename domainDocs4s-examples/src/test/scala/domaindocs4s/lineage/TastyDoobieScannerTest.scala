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

    "detects streaming queries returning fs2.Stream[ConnectionIO, T]" in {
      val streamMethods = doobieIntegrations.filter(_.method.methodName == "streamTransactions")
      streamMethods should have size 1
      streamMethods.head.accessType shouldBe DataAccessType.Read
      streamMethods.head.target shouldBe "transactions"
    }

    "detects doobie queries in methods not returning ConnectionIO (IO after .transact)" in {
      val directDbMethods = doobieIntegrations.filter(_.method.className == "DirectDbAccess")
      directDbMethods should have size 1
      directDbMethods.head.method.methodName shouldBe "getBalanceIO"
      directDbMethods.head.accessType shouldBe DataAccessType.Read
      directDbMethods.head.target shouldBe "users"
    }

    "detects doobie queries inside val initializers" in {
      val valMethods = doobieIntegrations.filter(_.method.className == "InlineQueryHolder")
      valMethods should have size 1
      valMethods.head.method.methodName shouldBe "activeUserCount"
      valMethods.head.accessType shouldBe DataAccessType.Read
      valMethods.head.target shouldBe "users"
    }

    "detects Update[A](sql).updateMany as Write" in {
      val batchMethods = doobieIntegrations.filter(_.method.className == "BatchUpdateRepo")
      batchMethods should have size 1
      batchMethods.head.method.methodName shouldBe "batchInsert"
      batchMethods.head.accessType shouldBe DataAccessType.Write
      batchMethods.head.target shouldBe "daily_balance_change"
    }

    "detects doobie patterns inside if/else branches" in {
      val conditionalMethods = doobieIntegrations.filter(_.method.className == "ConditionalUpdateRepo")
      conditionalMethods should have size 1
      conditionalMethods.head.method.methodName shouldBe "upsertIfNotEmpty"
      conditionalMethods.head.accessType shouldBe DataAccessType.Write
      conditionalMethods.head.target shouldBe "conditional_table"
    }

    "detects doobie patterns inside match branches" in {
      val matchMethods = doobieIntegrations.filter(_.method.className == "MatchUpdateRepo")
      matchMethods should have size 3
      matchMethods.foreach(_.target shouldBe "match_table")

      val writes = matchMethods.filter(_.accessType == DataAccessType.Write)
      writes should have size 2
      writes.map(_.method.methodName).toSet shouldBe Set("upsertByType")

      val reads = matchMethods.filter(_.accessType == DataAccessType.Read)
      reads should have size 1
      reads.head.method.methodName shouldBe "upsertByType"
    }

    "skips SQL keywords like unnest in table name extraction" in {
      val unnestMethods = doobieIntegrations.filter(_.method.className == "UnnestQueryRepo")
      unnestMethods should have size 1
      unnestMethods.head.method.methodName shouldBe "getExpanded"
      unnestMethods.head.accessType shouldBe DataAccessType.Read
      unnestMethods.head.target shouldBe "keyword_test_table"
    }

    "detects fr\"...\" Fragment interpolator with .update.run as Write" in {
      val frMethods = doobieIntegrations.filter(_.method.className == "FragmentRepo")
      val writes = frMethods.filter(_.accessType == DataAccessType.Write)
      writes should have size 3
      writes.map(_.method.methodName).toSet shouldBe Set("upsert", "deleteUser", "upsertMargin")
      writes.foreach(_.target shouldBe "fr_test_table")
    }

    "detects fr\"\"\"...stripMargin with SQL keyword on subsequent margin line" in {
      val frMethods = doobieIntegrations.filter(_.method.className == "FragmentRepo")
      val marginMethod = frMethods.filter(_.method.methodName == "upsertMargin")
      marginMethod should have size 1
      marginMethod.head.accessType shouldBe DataAccessType.Write
      marginMethod.head.target shouldBe "fr_test_table"
    }

    "detects fr\"...\" Fragment interpolator with .query[T] as Read" in {
      val frMethods = doobieIntegrations.filter(_.method.className == "FragmentRepo")
      val reads = frMethods.filter(_.accessType == DataAccessType.Read)
      reads should have size 1
      reads.head.method.methodName shouldBe "getAmount"
      reads.head.target shouldBe "fr_test_table"
    }

    "enriched doobie integrations have group user-db" in {
      val enrichedDoobie = integrations.filter(i => i.scanner == "doobie" && i.method.className == "UserRepo")
      enrichedDoobie should not be empty
      enrichedDoobie.foreach(_.group shouldBe Some("user-db"))
    }

    "detects doobie queries in anonymous class inside companion object (trait+companion pattern)" in {
      val traitRepoIntegrations = doobieIntegrations.filter(_.method.className == "TraitRepo")
      traitRepoIntegrations should not be empty

      // Read via sql"..." in getItem (also found via apply method due to exhaustive tree traversal)
      val reads = traitRepoIntegrations.filter(_.accessType == DataAccessType.Read)
      reads.map(_.method.methodName) should contain("getItem")
      reads.foreach(_.target shouldBe "trait_repo_table")

      // Write via fr"...".stripMargin.update.run in upsertItem (also found via apply)
      val writes = traitRepoIntegrations.filter(_.accessType == DataAccessType.Write)
      writes.map(_.method.methodName) should contain("upsertItem")
      writes.foreach(_.target shouldBe "trait_repo_table")
    }
  }

}
