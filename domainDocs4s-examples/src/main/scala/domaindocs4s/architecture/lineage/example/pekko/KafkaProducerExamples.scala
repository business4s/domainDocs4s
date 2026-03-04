package domaindocs4s.architecture.lineage.example.pekko

import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.kafka.ProducerSettings

// ============================================================================
// Example Pekko Kafka producer classes for TASTy scanning.
//
// These classes use Producer methods that the TastyPekkoKafkaScanner detects:
//   KafkaFlexiFlowProducer  — uses Producer.flexiFlow
//   KafkaPlainSinkProducer  — uses Producer.plainSink
// ============================================================================

class KafkaFlexiFlowProducer(settings: ProducerSettings[String, String]) {

  def createFlow(): Any =
    Producer.flexiFlow(settings)
}

class KafkaPlainSinkProducer(settings: ProducerSettings[String, String]) {

  def createSink(): Any =
    Producer.plainSink(settings)
}
