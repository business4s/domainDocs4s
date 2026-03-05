package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyPekkoJournalScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
  private val pekkoIntegrations = new TastyPekkoJournalScanner().scan(List(pekkoPkg))

  "TastyPekkoJournalScanner" - {

    "detects classic PersistentActor as Write to journal" in {
      val classicWrites = pekkoIntegrations.filter { di =>
        di.method.className == "OrderActor" && di.accessType == DataAccessType.Write
      }
      classicWrites should have size 1
      classicWrites.head.target shouldBe "journal"
      classicWrites.head.evidence shouldBe "extends PersistentActor"
    }

    "detects typed EventSourcedBehavior as Write to journal" in {
      val typedWrites = pekkoIntegrations.filter { di =>
        di.method.className == "AccountBehavior" && di.accessType == DataAccessType.Write
      }
      typedWrites should have size 1
      typedWrites.head.target shouldBe "journal"
      typedWrites.head.evidence shouldBe "references EventSourcedBehavior"
    }

    "detects journal query usage as Read from journal" in {
      val reads = pekkoIntegrations.filter { di =>
        di.accessType == DataAccessType.Read && di.method.className == "EventProjection"
      }
      reads should have size 1
      reads.head.method.methodName shouldBe "streamByTag"
      reads.head.target shouldBe "journal"
    }

    "detects EventSourcedProvider usage as Read from journal" in {
      val espReads = pekkoIntegrations.filter { di =>
        di.method.className == "TagBasedProjection" && di.accessType == DataAccessType.Read
      }
      espReads should have size 1
      espReads.head.method.methodName shouldBe "createSource"
      espReads.head.evidence should include("EventSourcedProvider")
    }

    "detects PersistenceQuery usage as Read from journal" in {
      val pqReads = pekkoIntegrations.filter { di =>
        di.method.className == "QueryBasedProjection" && di.accessType == DataAccessType.Read
      }
      pqReads should have size 1
      pqReads.head.method.methodName shouldBe "createReader"
      pqReads.head.evidence should include("PersistenceQuery")
    }

    "all pekko integrations have resourceType database and scanner pekko-journal" in {
      pekkoIntegrations.foreach { di =>
        di.resourceType shouldBe ResourceType.Database
        di.scanner shouldBe "pekko-journal"
      }
    }

    "pekko integrations have no group by default" in {
      pekkoIntegrations.foreach(_.group shouldBe None)
    }

    "composes with LineageBuilder" in {
      val pekkoCallGraph = new TastyCallGraphExtractor().extract(pekkoPkg)
      val pekkoResult = LineageBuilder.build(pekkoCallGraph, pekkoIntegrations)

      pekkoResult.integrations should have size pekkoIntegrations.size
      val output = pekkoResult.prettyPrint
      println(output)
      output should include("pekko-journal")
    }
  }

}
