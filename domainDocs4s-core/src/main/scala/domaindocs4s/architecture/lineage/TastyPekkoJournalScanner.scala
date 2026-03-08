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
)(using ctx: Context) extends IntegrationScanner {

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    // Primary rules (inheritance + field calls) take precedence
    val primaryResults = scanPersistentActors(packages) ++ scanReadJournal(packages)
    val primaryMethods = primaryResults.map(_.method).toSet

    // Type-reference rules only emit if no primary rule already covered the method
    val typeRefResults = scanTypeRef(
      TypeMatcher("org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior"),
      DataAccessType.Write, packages,
    ) ++ scanTypeRef(
      TypeMatcher.oneOf(
        "org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider",
        "org.apache.pekko.persistence.query.PersistenceQuery",
      ),
      DataAccessType.Read, packages,
    )

    primaryResults ++ typeRefResults.filterNot(r => primaryMethods.contains(r.method))
  }

  // Write: class extends PersistentActor → single integration with methodName "receiveCommand"
  private def scanPersistentActors(packages: List[String]): List[DiscoveredIntegration] = {
    val search = SymbolSearch.ClassInheritance(TypeMatcher.oneOf(
      "org.apache.pekko.persistence.PersistentActor",
      "org.apache.pekko.persistence.AbstractPersistentActor",
    ))
    val finder = new SymbolUsageFinder(Seq(search))
    finder.findAll(packages).collect { case u: FoundUsage.InheritanceResult =>
      val ref = u.path.toMethodRef
      mkIntegration(MethodRef(ref.packageName, ref.className, "receiveCommand"), DataAccessType.Write, s"extends ${u.parentSimpleName}")
    }
  }

  // Read: ReadJournal field.method(...) → Read
  private def scanReadJournal(packages: List[String]): List[DiscoveredIntegration] = {
    val search = SymbolSearch.MethodCall(
      TypeMatcher.isOrInheritsFrom("org.apache.pekko.persistence.query.scaladsl.ReadJournal"),
    )
    val finder = new SymbolUsageFinder(Seq(search))
    finder.findAll(packages).collect { case u: FoundUsage.MethodCallResult =>
      mkIntegration(u.path.toMethodRef, DataAccessType.Read, s"calls ${u.receiverName}.${u.methodName}")
    }
  }

  // Type reference: find method calls on a matching type, dedup by MethodRef
  private def scanTypeRef(typeMatcher: TypeMatcher, accessType: DataAccessType, packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(SymbolSearch.MethodCall(typeMatcher)))
    val seen = scala.collection.mutable.Set.empty[MethodRef]
    finder.findAll(packages).collect { case u: FoundUsage.MethodCallResult => u }.flatMap { u =>
      val ref = u.path.toMethodRef
      if (seen.add(ref)) Some(mkIntegration(ref, accessType, s"references ${u.ownerSimpleName}"))
      else None
    }
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
