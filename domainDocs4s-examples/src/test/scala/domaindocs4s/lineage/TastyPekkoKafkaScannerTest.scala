package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyPekkoKafkaScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
  private val kafkaIntegrations = new TastyPekkoKafkaScanner().scan(List(pekkoPkg))

  "TastyPekkoKafkaScanner" - {

    "detects Producer usage (flexiFlow) as Write to kafka" in {
      val flexiFlowWrites = kafkaIntegrations.filter { di =>
        di.method.className == "KafkaFlexiFlowProducer" && di.accessType == DataAccessType.Write
      }
      flexiFlowWrites should have size 1
      flexiFlowWrites.head.resourceType shouldBe ResourceType.Kafka
      flexiFlowWrites.head.evidence should include("Producer")
    }

    "detects Producer usage (plainSink) as Write to kafka" in {
      val plainSinkWrites = kafkaIntegrations.filter { di =>
        di.method.className == "KafkaPlainSinkProducer" && di.accessType == DataAccessType.Write
      }
      plainSinkWrites should have size 1
      plainSinkWrites.head.resourceType shouldBe ResourceType.Kafka
      plainSinkWrites.head.evidence should include("Producer")
    }

    "all pekko-kafka integrations have scanner pekko-kafka and group Kafka" in {
      val kafkaOnly = kafkaIntegrations.filter(_.scanner == "pekko-kafka")
      kafkaOnly should not be empty
      kafkaOnly.foreach { di =>
        di.resourceType shouldBe ResourceType.Kafka
        di.group shouldBe Some("Kafka")
      }
    }

    "detects Producer usage via imported member (flexiFlow) as Write to kafka" in {
      val importedWrites = kafkaIntegrations.filter { di =>
        di.method.className == "KafkaImportedFlexiFlowProducer" && di.accessType == DataAccessType.Write
      }
      importedWrites should have size 1
      importedWrites.head.resourceType shouldBe ResourceType.Kafka
      importedWrites.head.evidence should include("Producer")
    }

    "target includes class and method name as placeholder" in {
      val flexiFlow = kafkaIntegrations.find(_.method.className == "KafkaFlexiFlowProducer")
      flexiFlow.get.target should include("KafkaFlexiFlowProducer")
      flexiFlow.get.target should include("createFlow")
    }
  }

}
