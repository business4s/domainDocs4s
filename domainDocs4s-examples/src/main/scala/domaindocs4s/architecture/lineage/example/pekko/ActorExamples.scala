package domaindocs4s.architecture.lineage.example.pekko

import org.apache.pekko.actor.typed.ActorRef

// ============================================================================
// Example classes with Pekko typed ActorRef usage for TASTy scanning.
//
// ActorTellSender   — sends via tell (Write)
// ActorBangSender   — sends via ! operator (Write)
// ActorMultiSender  — sends to multiple actor refs (Write)
// ============================================================================

/** Sends messages via ActorRef.tell — detected as Write by TastyPekkoActorScanner. */
class ActorTellSender(target: ActorRef[String]) {
  def sendMessage(msg: String): Unit =
    target.tell(msg)
}

/** Sends messages via ActorRef.! — detected as Write by TastyPekkoActorScanner. */
class ActorBangSender(target: ActorRef[String]) {
  def sendMessage(msg: String): Unit =
    target ! msg
}

/** Sends to multiple actor refs — should still produce one integration per method. */
class ActorMultiSender(first: ActorRef[String], second: ActorRef[String]) {
  def broadcast(msg: String): Unit = {
    first.tell(msg)
    second.tell(msg)
  }
}
