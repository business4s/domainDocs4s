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

class TastyPekkoKafkaScanner(using ctx: Context) extends IntegrationScanner {

  private val search = SymbolSearch.MethodCall(
    TypeMatcher("org.apache.pekko.kafka.scaladsl.Producer"),
  )

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(search))
    val usages = finder.findAll(packages).collect { case u: FoundUsage.MethodCallResult => u }

    // Dedup: emit at most one per method (first match)
    val seen = scala.collection.mutable.Set.empty[MethodRef]
    usages.flatMap { u =>
      val ref = u.path.toMethodRef
      if (seen.add(ref)) {
        Some(DiscoveredIntegration(
          method = ref,
          accessType = DataAccessType.Write,
          resourceType = ResourceType.Kafka,
          scanner = "pekko-kafka",
          target = s"unknown topic from ${ref.className}.${ref.methodName}",
          evidence = s"references ${u.ownerSimpleName}",
          group = Some("Kafka"),
        ))
      } else None
    }
  }
}
