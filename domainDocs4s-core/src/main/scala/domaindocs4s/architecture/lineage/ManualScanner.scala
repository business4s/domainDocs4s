package domaindocs4s.architecture.lineage

import domaindocs4s.macros.MethodRefMacro
import scala.collection.mutable.ListBuffer

// ============================================================================
// Manual Scanner
//
// Declares integrations that can't be detected automatically from TASTy.
// Produces List[DiscoveredIntegration] — same output as automatic scanners,
// composable via concatenation before passing to LineageBuilder.
//
// Usage:
//   val manual = ManualScanner.builder
//     .method[KafkaHandler](_.handle).writes.kafka("ledger.movements")
//     .method[EventConsumer](_.consume).reads.kafka("input.events", cluster = "Analytics")
//     .method[S3Exporter](_.export).writes.custom("s3", "my-bucket/exports")
//     .build
// ============================================================================

object ManualScanner {

  def builder: Builder = new Builder

  class Builder {
    private val integrations = ListBuffer.empty[DiscoveredIntegration]

    inline def method[T](inline selector: T => Any): MethodBuilder = {
      val (className, methodName) = MethodRefMacro.extract[T](selector)
      new MethodBuilder(className, methodName)
    }

    def build: IntegrationScanner = {
      val result = integrations.toList
      new IntegrationScanner {
        def scan(packages: List[String]): List[DiscoveredIntegration] = result
      }
    }

    class MethodBuilder(className: String, methodName: String) {
      def reads: IntegrationBuilder = new IntegrationBuilder(className, methodName, DataAccessType.Read)
      def writes: IntegrationBuilder = new IntegrationBuilder(className, methodName, DataAccessType.Write)
    }

    class IntegrationBuilder(className: String, methodName: String, accessType: DataAccessType) {

      def kafka(topic: String, cluster: String = "Kafka"): Builder =
        custom(resourceType = "kafka", target = topic, group = Some(cluster))

      def custom(
          resourceType: String,
          target: String,
          group: Option[String] = None,
          evidence: String = "manual declaration",
      ): Builder = {
        integrations += DiscoveredIntegration(
          method = MethodRef(className, methodName),
          accessType = accessType,
          resourceType = resourceType,
          scanner = "manual",
          target = target,
          evidence = evidence,
          group = group,
        )
        Builder.this
      }
    }
  }
}
