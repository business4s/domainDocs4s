package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*

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

  private val ProducerName = "Producer"

  def scan(packages: List[String]): List[DiscoveredIntegration] =
    packages.flatMap(scanPackage)

  private def scanPackage(packageName: String): List[DiscoveredIntegration] = {
    val pkg = ctx.findPackage(packageName)
    val classes = TastyUtils.userClasses(pkg)
    val objects = TastyUtils.moduleClasses(pkg)
    (classes ++ objects).flatMap(scanClass(packageName, _))
  }

  private def scanClass(packageName: String, cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString.stripSuffix("$")
    cls.declarations.collect {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        ts.tree.toList.flatMap {
          case defDef: DefDef =>
            defDef.rhs.toList.flatMap { rhs =>
              val detector = new ProducerUsageDetector
              detector.traverse(rhs)
              if (detector.found) List(DiscoveredIntegration(
                method = MethodRef(packageName, className, methodName),
                accessType = DataAccessType.Write,
                resourceType = ResourceType.Kafka,
                scanner = "pekko-kafka",
                target = s"unknown topic from $className.$methodName",
                evidence = detector.evidence,
                group = Some("Kafka"),
              ))
              else Nil
            }
          case _ => Nil
        }
    }.flatten
  }

  /** TreeTraverser that detects any reference to the Producer object.
    *
    * Matches Select(qual, method) where qual is a Producer reference (captures method name),
    * and bare Ident/Select references to Producer as a fallback.
    */
  private class ProducerUsageDetector extends TreeTraverser {
    var found: Boolean = false
    private var calledMethod: Option[String] = None

    def evidence: String = calledMethod match {
      case Some(m) => s"calls Producer.$m"
      case None    => "references Producer"
    }

    override def traverse(tree: Tree): Unit = {
      if (!found) {
        tree match {
          case Select(qual, method) if isProducerRef(qual) =>
            found = true
            calledMethod = Some(TastyUtils.simpleName(method))
          case Ident(name) if TastyUtils.simpleName(name) == ProducerName =>
            found = true
          case Select(_, name) if TastyUtils.simpleName(name) == ProducerName =>
            found = true
          case _ =>
        }
        if (!found) super.traverse(tree)
      }
    }

    private def isProducerRef(tree: Tree): Boolean = tree match {
      case Ident(name)    => TastyUtils.simpleName(name) == ProducerName
      case Select(_, name) => TastyUtils.simpleName(name) == ProducerName
      case _              => false
    }
  }
}
