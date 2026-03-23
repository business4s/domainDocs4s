package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

// ============================================================================
// TASTy-based Pekko/Akka Actor Scanner
//
// Scans compiled Scala code via TASTy to find ActorRef tell/ask usage.
// Output: "classA.methodB writes/readWrites actor"
//
// Detects method calls on ActorRef types (both typed and classic):
//   actorRef.tell(msg)  → Write to actor
//   actorRef ! msg      → Write to actor
//   actorRef.ask(msg)   → ReadWrite to actor
//   actorRef ? msg      → ReadWrite to actor
//
// Actor identity is not known at scan time — the scanner produces unresolved
// targets. Use LineageAdjustments with .actor("name") to specify targets.
// ============================================================================

class TastyPekkoActorScanner()(using ctx: Context)
    extends IntegrationScanner {

  private val writeMethods = Set("tell", "!", "forward")
  private val askMethods   = Set("ask", "?")

  private val search = SymbolSearch.MethodCall(
    TypeMatcher.oneOf(
      "org.apache.pekko.actor.typed.ActorRef",
      "org.apache.pekko.actor.typed.RecipientRef",
      "org.apache.pekko.actor.ActorRef",
      "akka.actor.typed.ActorRef",
      "akka.actor.typed.RecipientRef",
      "akka.actor.ActorRef",
    ),
  )

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(search))
    val usages = finder.findAll(packages).collect { case u: FoundUsage.MethodCallResult => u }

    val seen = scala.collection.mutable.Set.empty[MethodRef]
    usages.flatMap { u =>
      val accessType =
        if (writeMethods.contains(u.methodName)) Some(DataAccessType.Write)
        else if (askMethods.contains(u.methodName)) Some(DataAccessType.ReadWrite)
        else None
      accessType.flatMap { at =>
        val ref = u.path.toMethodRef
        if (seen.add(ref)) {
          Some(
            DiscoveredIntegration(
              method = ref,
              accessType = at,
              resourceId = ResourceId.ActorTarget(
                actor = s"<unresolved:${ref.className}.${ref.methodName}>",
              ),
              scanner = "pekko-actor",
              evidence = s"calls ${u.receiverName}.${u.methodName}",
            ),
          )
        } else None
      }
    }
  }
}
