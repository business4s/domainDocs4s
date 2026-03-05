package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

// ============================================================================
// TASTy-based Pekko Kafka Scanner
//
// Scans compiled Scala code via TASTy to find Pekko Kafka producer usage.
// Output: "classA.methodB writes kafka"
//
// Detects any reference to the Producer object (any method):
//   Producer.flexiFlow        → Write to kafka
//   Producer.plainSink        → Write to kafka
//   Producer.committableSink  → Write to kafka
//   Producer.<any>            → Write to kafka
// ============================================================================

class TastyPekkoKafkaScanner(using ctx: Context) extends DeclarativeScanner(
  name = "pekko-kafka",
  resourceType = ResourceType.Kafka,
  rules = Seq(
    DetectionRule.TypeReference(
      targetType = TypeMatcher("org.apache.pekko.kafka.scaladsl.Producer"),
      accessType = DataAccessType.Write,
      targetNaming = TargetNaming.MethodPlaceholder,
    ),
  ),
  group = Some("Kafka"),
)
