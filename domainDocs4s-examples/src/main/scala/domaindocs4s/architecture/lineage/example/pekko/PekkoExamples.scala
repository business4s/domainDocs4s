package domaindocs4s.architecture.lineage.example.pekko

import org.apache.pekko.persistence.PersistentActor
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.query.{EventEnvelope, Offset}
import org.apache.pekko.persistence.query.scaladsl.EventsByTagQuery
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.NotUsed

// ============================================================================
// Example Pekko persistence classes for TASTy scanning.
//
// Write side:
//   OrderActor    — classic PersistentActor (extends PersistentActor)
//   AccountBehavior — typed EventSourcedBehavior (factory method)
//
// Read side:
//   EventProjection — reads journal via ReadJournal field
// ============================================================================

// ── Write side: Classic persistent actor ────────────────────────────────────

class OrderActor extends PersistentActor {
  override def persistenceId: String = "order-actor"

  private var orders: List[String] = Nil

  override def receiveCommand: Receive = { case order: String =>
    persist(order) { evt => orders = evt :: orders }
  }

  override def receiveRecover: Receive = { case order: String =>
    orders = order :: orders
  }
}

// ── Write side: Typed event-sourced behavior ────────────────────────────────

object AccountBehavior {

  sealed trait Command
  case class Deposit(amount: BigDecimal) extends Command

  sealed trait Event
  case class Deposited(amount: BigDecimal) extends Event

  case class AccountState(balance: BigDecimal)

  def apply(accountId: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, AccountState](
      persistenceId = PersistenceId.ofUniqueId(accountId),
      emptyState = AccountState(0),
      commandHandler = (_, cmd) =>
        cmd match {
          case Deposit(amount) => Effect.persist(Deposited(amount))
        },
      eventHandler = (state, evt) =>
        evt match {
          case Deposited(amount) => AccountState(state.balance + amount)
        },
    )
}

// ── Read side: Journal query consumer ───────────────────────────────────────

class EventProjection(val journal: EventsByTagQuery) {

  def streamByTag(tag: String): Source[EventEnvelope, NotUsed] =
    journal.eventsByTag(tag, Offset.noOffset)
}
