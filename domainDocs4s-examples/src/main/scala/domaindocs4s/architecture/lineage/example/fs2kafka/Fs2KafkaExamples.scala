package domaindocs4s.architecture.lineage.example.fs2kafka

import cats.effect.IO
import fs2.kafka.*

// ============================================================================
// Example classes with real fs2-kafka types for TASTy scanning.
//
// Fs2KafkaEventPublisher  — FieldMethodCall: producer.produce (Write)
// Fs2KafkaEventConsumer   — FieldMethodCall: consumer.subscribeTo (Read)
// Fs2KafkaStreamProducer  — TypeReference: KafkaProducer.stream (Write)
// Fs2KafkaStreamConsumer  — TypeReference: KafkaConsumer.stream (Read)
//
// Topic names are not extracted (they come from config); use LineageAdjustments
// with .kafka("topic-name") to specify topic targets.
// ============================================================================

/** Writes to Kafka via injected producer — detected as Write by FieldMethodCall. */
class Fs2KafkaEventPublisher(val producer: KafkaProducer[IO, String, String]) {

  def publishEvent(key: String, value: String): IO[IO[ProducerResult[String, String]]] = {
    val record = ProducerRecord("events", key, value)
    producer.produce(fs2.Chunk.singleton(record))
  }
}

/** Reads from Kafka via injected consumer — detected as Read by FieldMethodCall. */
class Fs2KafkaEventConsumer(val consumer: KafkaConsumer[IO, String, String]) {

  def consumeEvents(): IO[Unit] =
    consumer.subscribeTo("events")
}

/** Uses KafkaProducer companion — detected as Write by TypeReference. */
class Fs2KafkaStreamProducer {

  def createProducerStream(settings: ProducerSettings[IO, String, String]): Any =
    KafkaProducer.stream(settings)
}

/** Uses KafkaConsumer companion — detected as Read by TypeReference. */
class Fs2KafkaStreamConsumer {

  def createConsumerStream(settings: ConsumerSettings[IO, String, String]): Any =
    KafkaConsumer.stream(settings)
}
