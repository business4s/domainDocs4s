package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Trees.*

// ============================================================================
// TASTy-based fs2-kafka Scanner
//
// Scans compiled Scala code via TASTy to find fs2-kafka producer and consumer
// usage. Output: "classA.methodB reads/writes kafka"
//
// Detection:
//   MethodCall — fields of type KafkaProducer/TransactionalKafkaProducer
//     (Write) or KafkaConsumer (Read), with specific method calls.
//   MethodCall — companion object references for static factory patterns
//     like KafkaProducer.stream(settings) or KafkaConsumer.stream(settings).
//
// Topic names come from config, not code — the scanner produces generic
// targets. Use LineageAdjustments with .kafka("topic-name") to specify topics.
// ============================================================================

class TastyFs2KafkaScanner(
    group: Option[String] = Some("Kafka"),
)(using ctx: Context) extends IntegrationScanner {

  private val producerType = TypeMatcher.oneOf(
    "fs2.kafka.KafkaProducer",
    "fs2.kafka.TransactionalKafkaProducer",
  )
  private val consumerType = TypeMatcher("fs2.kafka.KafkaConsumer")
  //> I dont like this, its too strict. Any call to producer should me produce, and call co conumer means read.
  //> If we need specific method support for parms extraction, it should be a layer on top. But search look for any method call
  private val producerWriteMethods = Set("produce")
  private val consumerReadMethods = Set("subscribeTo")

  private val producerSearch = SymbolSearch.MethodCall(producerType)
  private val consumerSearch = SymbolSearch.MethodCall(consumerType)

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(producerSearch, consumerSearch))
    val usages = finder.findAll(packages).collect { case u: FoundUsage.MethodCallResult => u }

    // Phase 1: field method calls (higher priority)
    val fieldResults = usages.flatMap(interpretFieldCall)
    val fieldMethods = fieldResults.map(_.method).toSet

    // Phase 2: companion/factory references (only if no field call matched for that method)
    val seen = scala.collection.mutable.Set.empty[MethodRef]
    val factoryResults = usages.flatMap { u =>
      interpretFactoryRef(u).filter { di =>
        !fieldMethods.contains(di.method) && seen.add(di.method)
      }
    }

    fieldResults ++ factoryResults
  }

  // Injected producer/consumer fields: producer.produce(...), consumer.subscribeTo(...)
  private def interpretFieldCall(u: FoundUsage.MethodCallResult): Option[DiscoveredIntegration] = {
    val ref = u.path.toMethodRef
    val (accessType, isMatch) = u.search match {
      case s if s == producerSearch && producerWriteMethods.contains(u.methodName) =>
        (DataAccessType.Write, true)
      case s if s == consumerSearch && consumerReadMethods.contains(u.methodName) =>
        (DataAccessType.Read, true)
      case _ =>
        (DataAccessType.Read, false)
    }
    if (isMatch) {
      Some(DiscoveredIntegration(
        method = ref,
        accessType = accessType,
        resourceType = ResourceType.Kafka,
        scanner = "fs2-kafka",
        target = "Kafka",
        evidence = s"calls ${extractFieldName(u.receiverTree)}.${u.methodName}",
        group = group,
      ))
    } else None
  }

  // Static factory: KafkaProducer.stream(settings), KafkaConsumer.stream(settings)
  private def interpretFactoryRef(u: FoundUsage.MethodCallResult): Option[DiscoveredIntegration] = {
    val ref = u.path.toMethodRef
    val accessType = u.search match {
      case s if s == producerSearch => DataAccessType.Write
      case s if s == consumerSearch => DataAccessType.Read
      case _                       => return None
    }
    Some(DiscoveredIntegration(
      method = ref,
      accessType = accessType,
      resourceType = ResourceType.Kafka,
      scanner = "fs2-kafka",
      target = s"kafka from ${ref.className}.${ref.methodName}",
      evidence = s"references ${u.ownerSimpleName}",
      group = group,
    ))
  }

  private def extractFieldName(tree: Tree): String = tree match {
    case Ident(name)           => TastyUtils.simpleName(name)
    case Select(_: This, name) => TastyUtils.simpleName(name)
    case Select(_, name)       => TastyUtils.simpleName(name)
    case _                     => "?"
  }
}
