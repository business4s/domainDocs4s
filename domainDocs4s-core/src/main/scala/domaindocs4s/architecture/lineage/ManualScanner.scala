package domaindocs4s.architecture.lineage

import domaindocs4s.macros.MethodRefMacro
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag

// ============================================================================
// Manual Scanner
//
// Declares integrations that can't be detected automatically from TASTy,
// and refines auto-detected integrations via override semantics.
//
// Method-level (upsert): if a manual entry matches an auto-detected integration
// (same className + methodName + resourceType), it replaces the target.
// If no match, it's added as new.
//
// Class-level (replace): replaces ALL auto-detected integrations of that
// resourceType for the class, creating a cross-product of detected methods
// x override targets.
//
// Usage:
//   val manual = ManualScanner.builder
//     // Method-level (upsert: override if match, add if not)
//     .method[KafkaHandler](_.handle).writes.kafka("ledger.movements")
//     .method[EventConsumer](_.consume).reads.kafka("input.events", cluster = "Analytics")
//     .method[S3Exporter](_.export).writes.custom("s3", "my-bucket/exports")
//     // Class-level (replace all of resourceType for the class)
//     .cls[KafkaMovementHandler].writes.kafka("topic.a", "topic.b")
//     .cls[S3Handler].writes.custom("s3", "bucket-a", "bucket-b")
//     .build
// ============================================================================

object ManualScanner {

  def builder: Builder = new Builder

  class Builder {
    private val integrations = ListBuffer.empty[DiscoveredIntegration]
    private val classOverrides = ListBuffer.empty[ManualClassOverride]
    private var _strict: Boolean = true

    inline def method[T](inline selector: T => Any): MethodBuilder = {
      val (className, methodName) = MethodRefMacro.extract[T](selector)
      new MethodBuilder(className, methodName)
    }

    def cls[T: ClassTag]: ClassBuilder = {
      val className = summon[ClassTag[T]].runtimeClass.getSimpleName.stripSuffix("$")
      new ClassBuilder(className)
    }

    def lenient: Builder = { _strict = false; this }

    def build: ManualDeclarations =
      ManualDeclarations(
        methodEntries = integrations.toList,
        classOverrides = classOverrides.toList,
        strict = _strict,
      )

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

    class ClassBuilder(className: String) {
      def reads: ClassIntegrationBuilder = new ClassIntegrationBuilder(className, DataAccessType.Read)
      def writes: ClassIntegrationBuilder = new ClassIntegrationBuilder(className, DataAccessType.Write)
    }

    class ClassIntegrationBuilder(className: String, accessType: DataAccessType) {

      def kafka(topics: String*): Builder =
        custom("kafka", Some("Kafka"), topics*)

      def custom(resourceType: String, targets: String*): Builder =
        custom(resourceType, None, targets*)

      def custom(resourceType: String, group: Option[String], targets: String*): Builder = {
        classOverrides += ManualClassOverride(
          className = className,
          resourceType = resourceType,
          targets = targets.toList.map(t => ManualClassTarget(t, accessType, group)),
        )
        Builder.this
      }
    }
  }
}

case class ManualClassTarget(
    target: String,
    accessType: DataAccessType,
    group: Option[String],
)

case class ManualClassOverride(
    className: String,
    resourceType: String,
    targets: List[ManualClassTarget],
)

case class ManualDeclarations(
    methodEntries: List[DiscoveredIntegration] = Nil,
    classOverrides: List[ManualClassOverride] = Nil,
    strict: Boolean = true,
) {

  def apply(autoDetected: List[DiscoveredIntegration]): List[DiscoveredIntegration] = {
    // Step 1: Class-level overrides
    val afterClassOverrides = applyClassOverrides(autoDetected)

    // Step 2: Method-level upserts
    applyMethodUpserts(afterClassOverrides)
  }

  private def applyClassOverrides(autoDetected: List[DiscoveredIntegration]): List[DiscoveredIntegration] = {
    var result = autoDetected

    val unmatched = ListBuffer.empty[ManualClassOverride]

    for (co <- classOverrides) {
      val (matching, remaining) = result.partition { di =>
        di.method.className == co.className && di.resourceType == co.resourceType
      }

      if (matching.isEmpty) {
        unmatched += co
      } else {
        val detectedMethods = matching.map(_.method).distinct
        val replacements = for {
          method <- detectedMethods
          target <- co.targets
        } yield DiscoveredIntegration(
          method = method,
          accessType = target.accessType,
          resourceType = co.resourceType,
          scanner = "manual",
          target = target.target,
          evidence = "class-level declaration",
          group = target.group,
        )
        result = remaining ++ replacements
      }
    }

    if (strict && unmatched.nonEmpty) {
      throw ManualOverrideError(unmatched.toList)
    }

    result
  }

  private def applyMethodUpserts(current: List[DiscoveredIntegration]): List[DiscoveredIntegration] = {
    var result = current

    for (entry <- methodEntries) {
      val matchIdx = result.indexWhere { di =>
        di.method.className == entry.method.className &&
        di.method.methodName == entry.method.methodName &&
        di.resourceType == entry.resourceType
      }

      if (matchIdx >= 0) {
        result = result.updated(matchIdx, entry)
      } else {
        result = result :+ entry
      }
    }

    result
  }
}

object ManualDeclarations {
  val empty: ManualDeclarations = ManualDeclarations()
}

case class ManualOverrideError(unmatched: List[ManualClassOverride])
    extends RuntimeException(
      "Class-level overrides with no matching auto-detected integration:\n" +
        unmatched.map(co => s"  - ${co.className} (${co.resourceType})").mkString("\n") +
        "\nDid you forget to add the corresponding scanner? Use .lenient to suppress."
    )
