package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

// ============================================================================
// TASTy-based fs2-kafka Scanner
//
// Scans compiled Scala code via TASTy to find fs2-kafka producer and consumer
// usage. Output: "classA.methodB reads/writes kafka"
//
// Detection:
//   FieldMethodCall — fields of type KafkaProducer/TransactionalKafkaProducer
//     (Write) or KafkaConsumer (Read), with specific method calls.
//   TypeReference — companion object references for static factory patterns
//     like KafkaProducer.stream(settings) or KafkaConsumer.stream(settings).
//
// Topic names come from config, not code — the scanner produces generic
// targets. Use LineageAdjustments with .kafka("topic-name") to specify topics.
// ============================================================================

class TastyFs2KafkaScanner(
    group: Option[String] = Some("Kafka"),
)(using ctx: Context) extends DeclarativeScanner(
  name = "fs2-kafka",
  resourceType = ResourceType.Kafka,
  rules = Seq(
    // Injected producer fields: producer.produce(...)
    DetectionRule.FieldMethodCall(
      fieldType = TypeMatcher.oneOf(
        "fs2.kafka.KafkaProducer",
        "fs2.kafka.TransactionalKafkaProducer",
      ),
      methods = MethodMapping.Named(
        writeMethods = Set("produce"),
      ),
    ),
    // Injected consumer fields: consumer.subscribeTo(...)
    // Note: stream/records/partitionedRecords are no-arg properties — the FieldMethodCall
    // collector only matches Apply nodes (calls with arguments). For property-style consumer
    // access, the TypeReference rule below catches KafkaConsumer references.
    DetectionRule.FieldMethodCall(
      fieldType = TypeMatcher("fs2.kafka.KafkaConsumer"),
      methods = MethodMapping.Named(
        readMethods = Set("subscribeTo"),
      ),
    ),
    // Static factory: KafkaProducer.stream(settings), KafkaProducer.resource(settings)
    DetectionRule.TypeReference(
      targetType = TypeMatcher("fs2.kafka.KafkaProducer"),
      accessType = DataAccessType.Write,
      target = Some(m => s"kafka from ${m.className}.${m.methodName}"),
    ),
    // Static factory: KafkaConsumer.stream(settings)
    DetectionRule.TypeReference(
      targetType = TypeMatcher("fs2.kafka.KafkaConsumer"),
      accessType = DataAccessType.Read,
      target = Some(m => s"kafka from ${m.className}.${m.methodName}"),
    ),
  ),
  defaultTarget = "Kafka",
  group = group,
)
