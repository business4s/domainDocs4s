package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.example.fs2kafka.Fs2KafkaEventPublisher
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyFs2KafkaScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example.fs2kafka"

  private val integrations = new TastyFs2KafkaScanner().scan(List(pkg))

  "TastyFs2KafkaScanner" - {

    "detects producer.produce as Write" in {
      val writes = integrations.filter { di =>
        di.method.className == "Fs2KafkaEventPublisher" && di.accessType == DataAccessType.Write
      }
      writes should have size 1
      writes.head.evidence should include("produce")
    }

    "detects consumer.subscribeTo as Read" in {
      val reads = integrations.filter { di =>
        di.method.className == "Fs2KafkaEventConsumer" && di.accessType == DataAccessType.Read
      }
      reads should have size 1
      reads.head.evidence should include("subscribeTo")
    }

    "detects KafkaProducer static factory as Write" in {
      val writes = integrations.filter { di =>
        di.method.className == "Fs2KafkaStreamProducer" && di.accessType == DataAccessType.Write
      }
      writes should have size 1
      writes.head.target should include("Fs2KafkaStreamProducer")
    }

    "detects KafkaConsumer static factory as Read" in {
      val reads = integrations.filter { di =>
        di.method.className == "Fs2KafkaStreamConsumer" && di.accessType == DataAccessType.Read
      }
      reads should have size 1
      reads.head.target should include("Fs2KafkaStreamConsumer")
    }

    "all fs2-kafka integrations have correct scanner and resourceType" in {
      integrations should not be empty
      integrations.foreach { di =>
        di.resourceType shouldBe ResourceType.Kafka
        di.scanner shouldBe "fs2-kafka"
      }
    }

    "all fs2-kafka integrations have group Kafka" in {
      integrations.foreach(_.group shouldBe Some("Kafka"))
    }

    "LineageAdjustments .kafka(topic) overrides auto-detected targets" in {
      val adj = LineageAdjustments.builder
        .cls[Fs2KafkaEventPublisher]
        .removeIntegrations(ResourceType.Kafka)
        .cls[Fs2KafkaEventPublisher]
        .writes
        .kafka("user.events")
        .build

      val (_, result)      = adj.apply(Nil, integrations)
      val publisherResults = result.filter(_.method.className == "Fs2KafkaEventPublisher")
      publisherResults should have size 1
      publisherResults.head.target shouldBe "user.events"
      publisherResults.head.scanner shouldBe "manual"
      publisherResults.head.resourceType shouldBe ResourceType.Kafka
    }
  }

}
