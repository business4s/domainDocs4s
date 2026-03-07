package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Trees.*

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
)(using ctx: Context) extends IntegrationScanner {

  // Inheritance: class extends PersistentActor
  private val persistentActorSearch = SymbolSearch.ClassInheritance(TypeMatcher.oneOf(
    "org.apache.pekko.persistence.PersistentActor",
    "org.apache.pekko.persistence.AbstractPersistentActor",
  ))

  // Field method calls: ReadJournal field.method(...)
  private val readJournalSearch = SymbolSearch.MethodCall(
    TypeMatcher.isOrInheritsFrom("org.apache.pekko.persistence.query.scaladsl.ReadJournal"),
  )

  // Type references: EventSourcedBehavior (write), EventSourcedProvider/PersistenceQuery (read)
  private val eventSourcedBehaviorSearch = SymbolSearch.MethodCall(
    TypeMatcher("org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior"),
  )
  private val projectionSourceSearch = SymbolSearch.MethodCall(TypeMatcher.oneOf(
    "org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider",
    "org.apache.pekko.persistence.query.PersistenceQuery",
  ))

  //> I dont like that we search for everything at once. those are separate concern, separate algorithms, should live separately. performance is not that important here.
  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(
      persistentActorSearch, readJournalSearch, eventSourcedBehaviorSearch, projectionSourceSearch,
    ))
    val usages = finder.findAll(packages)

    // Phase 1: inheritance + field calls (higher priority)
    val inheritanceResults = usages.collect { case u: FoundUsage.InheritanceResult => u }.flatMap(interpretInheritance)
    val fieldResults = usages.collect { case u: FoundUsage.MethodCallResult if u.search == readJournalSearch => u }.map(interpretFieldCall)
    val primaryMethods = (inheritanceResults ++ fieldResults).map(_.method).toSet

    // Phase 2: type references (only if no primary rule matched for that method)
    val seen = scala.collection.mutable.Set.empty[MethodRef]
    val typeRefResults = usages.collect { case u: FoundUsage.MethodCallResult if u.search != readJournalSearch => u }.flatMap { u =>
      interpretTypeRef(u).filter { di =>
        !primaryMethods.contains(di.method) && seen.add(di.method)
      }
    }

    inheritanceResults ++ fieldResults ++ typeRefResults
  }

  // Write: class extends PersistentActor → single integration with methodName "receiveCommand"
  private def interpretInheritance(u: FoundUsage.InheritanceResult): List[DiscoveredIntegration] = {
    val ref = u.path.toMethodRef
    List(DiscoveredIntegration(
      method = MethodRef(ref.packageName, ref.className, "receiveCommand"),
      accessType = DataAccessType.Write,
      resourceType = ResourceType.Database,
      scanner = "pekko-journal",
      target = "journal",
      evidence = s"extends ${u.parentSimpleName}",
      group = group,
    ))
  }

  // Read: ReadJournal field.method(...) → Read
  private def interpretFieldCall(u: FoundUsage.MethodCallResult): DiscoveredIntegration = {
    val ref = u.path.toMethodRef
    DiscoveredIntegration(
      method = ref,
      accessType = DataAccessType.Read,
      resourceType = ResourceType.Database,
      scanner = "pekko-journal",
      target = "journal",
      evidence = s"calls ${extractFieldName(u.receiverTree)}.${u.methodName}",
      group = group,
    )
  }

  // Type references: EventSourcedBehavior → Write, EventSourcedProvider/PersistenceQuery → Read
  private def interpretTypeRef(u: FoundUsage.MethodCallResult): Option[DiscoveredIntegration] = {
    val ref = u.path.toMethodRef
    val accessType = u.search match {
      case s if s == eventSourcedBehaviorSearch => DataAccessType.Write
      case s if s == projectionSourceSearch     => DataAccessType.Read
      case _                                   => return None
    }
    Some(DiscoveredIntegration(
      method = ref,
      accessType = accessType,
      resourceType = ResourceType.Database,
      scanner = "pekko-journal",
      target = "journal",
      evidence = s"references ${u.ownerSimpleName}",
      group = group,
    ))
  }

  //> duplication with some other scanner?
  private def extractFieldName(tree: Tree): String = tree match {
    case Ident(name)           => TastyUtils.simpleName(name)
    case Select(_: This, name) => TastyUtils.simpleName(name)
    case Select(_, name)       => TastyUtils.simpleName(name)
    case _                     => "?"
  }
}
