package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

// ============================================================================
// TASTy-based fs2-kafka Scanner
//
// Scans compiled Scala code via TASTy to find fs2-kafka producer and consumer
// usage. Output: "classA.methodB reads/writes kafka"
//
// Detection: any method call on KafkaProducer/TransactionalKafkaProducer → Write,
// any method call on KafkaConsumer → Read. Covers both injected field usage
// (producer.produce(...)) and static factories (KafkaProducer.stream(settings)).
//
// Topic names come from config, not code — the scanner produces generic
// targets. Use LineageAdjustments with .kafka("topic-name") to specify topics.
// ============================================================================

class TastyFs2KafkaScanner(
    cluster: Option[String] = None,
)(using ctx: Context)
    extends IntegrationScanner {

  private val producerSearch = SymbolSearch.MethodCall(
    TypeMatcher.oneOf(
      "fs2.kafka.KafkaProducer",
      "fs2.kafka.TransactionalKafkaProducer",
    ),
  )
  private val consumerSearch = SymbolSearch.MethodCall(
    TypeMatcher("fs2.kafka.KafkaConsumer"),
  )

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(producerSearch, consumerSearch))
    val usages = finder.findAll(packages).collect { case u: FoundUsage.MethodCallResult => u }

    // Dedup: emit at most one per method
    val seen = scala.collection.mutable.Set.empty[MethodRef]
    usages.flatMap { u =>
      val ref = u.path.toMethodRef
      if (seen.add(ref)) {
        val accessType = u.search match {
          case s if s == producerSearch => DataAccessType.Write
          case s if s == consumerSearch => DataAccessType.Read
          case _                        => return Nil
        }
        val resourceId = ResourceId.KafkaTopic(
          topic = s"<unresolved:${ref.className}.${ref.methodName}>",
          cluster = cluster,
        )
        Some(
          DiscoveredIntegration(
            method = ref,
            accessType = accessType,
            resourceId = resourceId,
            scanner = "fs2-kafka",
            evidence = s"calls ${u.receiverName}.${u.methodName}",
          ),
        )
      } else None
    }
  }
}
