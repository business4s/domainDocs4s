package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyPekkoActorScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pekkoPkg          = "domaindocs4s.architecture.lineage.example.pekko"
  private val actorIntegrations = new TastyPekkoActorScanner().scan(List(pekkoPkg))

  "TastyPekkoActorScanner" - {

    "detects ActorRef.tell as Write to actor" in {
      val writes = actorIntegrations.filter { di =>
        di.method.className == "ActorTellSender" && di.accessType == DataAccessType.Write
      }
      writes should have size 1
      writes.head.resourceType shouldBe ResourceType.Actor
      writes.head.evidence should include("tell")
    }

    "detects ActorRef.! as Write to actor" in {
      val writes = actorIntegrations.filter { di =>
        di.method.className == "ActorBangSender" && di.accessType == DataAccessType.Write
      }
      writes should have size 1
      writes.head.resourceType shouldBe ResourceType.Actor
    }

    "deduplicates multiple tell calls in the same method" in {
      val writes = actorIntegrations.filter { di =>
        di.method.className == "ActorMultiSender" && di.accessType == DataAccessType.Write
      }
      writes should have size 1
    }

    "all pekko-actor integrations have scanner pekko-actor" in {
      val actorOnly = actorIntegrations.filter(_.scanner == "pekko-actor")
      actorOnly should not be empty
      actorOnly.foreach { di =>
        di.resourceType shouldBe ResourceType.Actor
      }
    }

    "target includes class and method name as unresolved placeholder" in {
      val tellSender = actorIntegrations.find(_.method.className == "ActorTellSender")
      tellSender.get.target should include("ActorTellSender")
      tellSender.get.target should include("sendMessage")
      tellSender.get.resourceId.isUnresolved shouldBe true
    }
  }
}
