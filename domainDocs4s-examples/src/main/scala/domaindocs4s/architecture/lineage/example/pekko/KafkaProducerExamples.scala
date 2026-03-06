package domaindocs4s.architecture.lineage.example.pekko

import org.apache.pekko.kafka.scaladsl.Producer
import org.apache.pekko.kafka.scaladsl.Producer.flexiFlow as importedFlexiFlow
import org.apache.pekko.kafka.ProducerSettings

// ============================================================================
// Example Pekko Kafka producer classes for TASTy scanning.
//
// These classes use Producer methods that the TastyPekkoKafkaScanner detects:
//   KafkaFlexiFlowProducer          — uses Producer.flexiFlow (qualified)
//   KafkaPlainSinkProducer          — uses Producer.plainSink (qualified)
//   KafkaImportedFlexiFlowProducer  — uses flexiFlow via import (unqualified)
// ============================================================================

class KafkaFlexiFlowProducer(settings: ProducerSettings[String, String]) {

  def createFlow(): Any =
    Producer.flexiFlow(settings)
}

class KafkaPlainSinkProducer(settings: ProducerSettings[String, String]) {

  def createSink(): Any =
    Producer.plainSink(settings)
}

class KafkaImportedFlexiFlowProducer(settings: ProducerSettings[String, String]) {

  def createFlow(): Any =
    importedFlexiFlow(settings)
}
