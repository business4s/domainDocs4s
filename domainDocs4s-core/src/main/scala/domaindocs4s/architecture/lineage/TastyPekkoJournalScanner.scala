package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

// ============================================================================
// TASTy-based Pekko Journal Scanner
//
// Scans compiled Scala code via TASTy to find Pekko persistence integrations.
// Output: "classA.methodB reads/writes journal"
//
// Write side — Classic persistent actors:
//   class extends PersistentActor / AbstractPersistentActor
//   → Write to journal
//
// Write side — Typed event-sourced behaviors:
//   method body references EventSourcedBehavior
//   → Write to journal
//
// Read side — Journal query consumers:
//   val field whose type has ReadJournal as ancestor
//   → each call to field.method(...) is a Read from journal
//
// Read side — Projection source providers:
//   method body references EventSourcedProvider or PersistenceQuery
//   → Read from journal
// ============================================================================

class TastyPekkoJournalScanner(
    group: Option[String] = None,
)(using ctx: Context)
    extends IntegrationScanner {

  // All searches declared upfront so a single SymbolUsageFinder walks TASTy once.
  private val persistentActorSearch      = SymbolSearch.ClassInheritance(
    TypeMatcher.oneOf(
      "org.apache.pekko.persistence.PersistentActor",
      "org.apache.pekko.persistence.AbstractPersistentActor",
    ),
  )
  private val readJournalSearch          = SymbolSearch.MethodCall(
    TypeMatcher.isOrInheritsFrom("org.apache.pekko.persistence.query.scaladsl.ReadJournal"),
  )
  private val eventSourcedBehaviorSearch = SymbolSearch.MethodCall(
    TypeMatcher("org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior"),
  )
  private val projectionSourceSearch     = SymbolSearch.MethodCall(
    TypeMatcher.oneOf(
      "org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider",
      "org.apache.pekko.persistence.query.PersistenceQuery",
    ),
  )

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(
      Seq(
        persistentActorSearch,
        readJournalSearch,
        eventSourcedBehaviorSearch,
        projectionSourceSearch,
      ),
    )
    val usages = finder.findAll(packages)

    // Primary rules (inheritance + field calls) take precedence
    val primaryResults = usages.flatMap {
      case u: FoundUsage.InheritanceResult if u.search == persistentActorSearch =>
        val ref = u.path.toMethodRef
        Some(mkIntegration(MethodRef(ref.packageName, ref.className, "receiveCommand"), DataAccessType.Write, s"extends ${u.parentSimpleName}"))
      case u: FoundUsage.MethodCallResult if u.search == readJournalSearch      =>
        Some(mkIntegration(u.path.toMethodRef, DataAccessType.Read, s"calls ${u.receiverName}.${u.methodName}"))
      case _                                                                    => None
    }
    val primaryMethods = primaryResults.map(_.method).toSet

    // Type-reference rules only emit if no primary rule already covered the method
    val seen           = scala.collection.mutable.Set.empty[MethodRef]
    val typeRefResults = usages.collect { case u: FoundUsage.MethodCallResult => u }.flatMap { u =>
      val (accessType, searchMatch) = u.search match {
        case s if s == eventSourcedBehaviorSearch => (DataAccessType.Write, true)
        case s if s == projectionSourceSearch     => (DataAccessType.Read, true)
        case _                                    => (DataAccessType.Pure, false)
      }
      if (!searchMatch) None
      else {
        val ref = u.path.toMethodRef
        if (primaryMethods.contains(ref) || !seen.add(ref)) None
        else Some(mkIntegration(ref, accessType, s"references ${u.ownerSimpleName}"))
      }
    }

    primaryResults ++ typeRefResults
  }

  private def mkIntegration(method: MethodRef, accessType: DataAccessType, evidence: String): DiscoveredIntegration =
    DiscoveredIntegration(
      method = method,
      accessType = accessType,
      resourceType = ResourceType.Database,
      scanner = "pekko-journal",
      target = "journal",
      evidence = evidence,
      group = group,
    )
}
