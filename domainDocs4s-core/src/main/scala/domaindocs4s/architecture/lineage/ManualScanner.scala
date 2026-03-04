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
// Both method-level and class-level entries work the same way:
//   1. Match auto-detected integrations by (className [+ methodName], resourceType)
//   2. If matches found → replace with cross-product of detected methods x manual targets
//   3. If no matches → strict (default) throws error; lenient adds as new (method) or skips (class)
//
// The only difference: method-level matches a single method, class-level matches all methods.
//
// Usage:
//   val manual = ManualScanner.builder
//     .method[KafkaHandler](_.handle).writes.kafka("ledger.movements")
//     .method[EventPublisher](_.publish).writes.kafka("events.topic").lenient
//     .cls[KafkaMovementHandler].writes.kafka("topic.a").kafka("topic.b")
//     .cls[S3Handler].writes.custom("s3", "bucket-a").custom("s3", "bucket-b")
//     .build
// ============================================================================

object ManualScanner {

  def builder: Builder = new Builder

  class Builder {
    private val _entries = ListBuffer.empty[ManualEntry]

    inline def method[T](inline selector: T => Any): MethodBuilder = {
      val (className, methodName) = MethodRefMacro.extract[T](selector)
      new MethodBuilder(className, methodName)
    }

    def cls[T: ClassTag]: ClassBuilder = {
      val className = summon[ClassTag[T]].runtimeClass.getSimpleName.stripSuffix("$")
      new ClassBuilder(className)
    }

    def build: ManualDeclarations = ManualDeclarations(_entries.toList)

    class MethodBuilder(className: String, methodName: String) {
      def reads: IntegrationBuilder = new IntegrationBuilder(className, Some(methodName), DataAccessType.Read)
      def writes: IntegrationBuilder = new IntegrationBuilder(className, Some(methodName), DataAccessType.Write)
    }

    class ClassBuilder(className: String) {
      def reads: IntegrationBuilder = new IntegrationBuilder(className, None, DataAccessType.Read)
      def writes: IntegrationBuilder = new IntegrationBuilder(className, None, DataAccessType.Write)
    }

    class IntegrationBuilder(className: String, methodName: Option[String], accessType: DataAccessType) {
      private val startIdx = _entries.length

      def kafka(topic: String): IntegrationBuilder = {
        _entries += ManualEntry(className, methodName, accessType, "kafka", topic, Some("Kafka"))
        this
      }

      def custom(resourceType: String, target: String, group: Option[String] = None): IntegrationBuilder = {
        _entries += ManualEntry(className, methodName, accessType, resourceType, target, group)
        this
      }

      def lenient: Builder = {
        for (i <- startIdx until _entries.length) {
          _entries.update(i, _entries(i).copy(strict = false))
        }
        Builder.this
      }

      inline def method[T](inline selector: T => Any): MethodBuilder = Builder.this.method[T](selector)
      def cls[T: ClassTag]: ClassBuilder = Builder.this.cls[T]
      def build: ManualDeclarations = Builder.this.build
    }
  }
}

case class ManualEntry(
    className: String,
    methodName: Option[String],
    accessType: DataAccessType,
    resourceType: String,
    target: String,
    group: Option[String],
    strict: Boolean = true,
)

case class ManualDeclarations(
    entries: List[ManualEntry] = Nil,
) {

  def apply(autoDetected: List[DiscoveredIntegration]): List[DiscoveredIntegration] = {
    val grouped = entries.groupBy(e => (e.className, e.methodName, e.resourceType))

    var result = autoDetected
    val unmatched = ListBuffer.empty[ManualEntry]

    for ((key, group) <- grouped) {
      val (className, methodName, resourceType) = key

      val (matching, remaining) = result.partition { di =>
        di.method.className == className &&
        methodName.forall(_ == di.method.methodName) &&
        di.resourceType == resourceType
      }

      if (matching.isEmpty) {
        val strictEntries = group.filter(_.strict)
        if (strictEntries.nonEmpty) {
          unmatched ++= strictEntries
        } else {
          // All lenient — method-level: add as new; class-level: skip silently
          methodName.foreach { mn =>
            result = result ++ group.map { e =>
              DiscoveredIntegration(
                method = MethodRef(className, mn),
                accessType = e.accessType,
                resourceType = resourceType,
                scanner = "manual",
                target = e.target,
                evidence = "manual declaration",
                group = e.group,
              )
            }
          }
        }
      } else {
        val detectedMethods = matching.map(_.method).distinct
        val replacements = for {
          method <- detectedMethods
          entry <- group
        } yield DiscoveredIntegration(
          method = method,
          accessType = entry.accessType,
          resourceType = resourceType,
          scanner = "manual",
          target = entry.target,
          evidence = "manual override",
          group = entry.group,
        )
        result = remaining ++ replacements
      }
    }

    if (unmatched.nonEmpty) {
      throw ManualOverrideError(unmatched.toList)
    }

    result
  }
}

object ManualDeclarations {
  val empty: ManualDeclarations = ManualDeclarations()
}

case class ManualOverrideError(unmatched: List[ManualEntry])
    extends RuntimeException(
      "Manual overrides with no matching auto-detected integration:\n" +
        unmatched
          .map { e =>
            val scope = e.methodName.map(m => s"${e.className}.$m").getOrElse(e.className)
            s"  - $scope (${e.resourceType})"
          }
          .mkString("\n") +
        "\nDid you forget to add the corresponding scanner? Use .lenient to suppress."
    )
