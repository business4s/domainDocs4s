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
)(using ctx: Context) extends DeclarativeScanner(
  name = "pekko-journal",
  resourceType = ResourceType.Database,
  rules = Seq(
    // Write: class extends PersistentActor
    DetectionRule.ClassExtends(
      parentType = TypeMatcher.oneOf(
        "org.apache.pekko.persistence.PersistentActor",
        "org.apache.pekko.persistence.AbstractPersistentActor",
      ),
      accessType = DataAccessType.Write,
      methodName = "receiveCommand",
    ),
    // Write: references EventSourcedBehavior
    DetectionRule.TypeReference(
      targetType = TypeMatcher("org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior"),
      accessType = DataAccessType.Write,
    ),
    // Read: field inheriting ReadJournal
    DetectionRule.FieldMethodCall(
      fieldType = TypeMatcher.isOrInheritsFrom("org.apache.pekko.persistence.query.scaladsl.ReadJournal"),
      methods = MethodMapping.AnyMethod(DataAccessType.Read),
    ),
    // Read: projection sources (EventSourcedProvider or PersistenceQuery)
    DetectionRule.TypeReference(
      targetType = TypeMatcher.oneOf(
        "org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider",
        "org.apache.pekko.persistence.query.PersistenceQuery",
      ),
      accessType = DataAccessType.Read,
    ),
  ),
  defaultTarget = "journal",
  group = group,
)
