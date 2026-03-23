package domaindocs4s.architecture.lineage.example.pekko

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.EventsByTagQuery
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider

// ============================================================================
// Example projection source classes for TASTy scanning.
//
// Read side:
//   TagBasedProjection   — uses EventSourcedProvider.eventsByTag
//   QueryBasedProjection — uses PersistenceQuery(...).readJournalFor
// ============================================================================

class TagBasedProjection(system: ActorSystem[?]) {
  def createSource(tag: String): Any =
    EventSourcedProvider.eventsByTag[String](system, "journal-plugin", tag)
}

class QueryBasedProjection(system: ActorSystem[?]) {
  def createReader(pluginId: String): Any =
    PersistenceQuery(system).readJournalFor[EventsByTagQuery](pluginId)
}
