package domaindocs4s.architecture.lineage.example.param

// ============================================================================
// Example classes for ParamValueIndex tests.
//
// Each group is isolated (separate target object per scenario) so that
// resolve() results are exact rather than cumulative.
// ============================================================================

// ── 1. Direct string literal ─────────────────────────────────────────────────

object DirectTarget {
  def consume(name: String): Unit = ()
}

class DirectLiteralUser {
  def run(): Unit = DirectTarget.consume("direct_value")
}

// ── 2. One-hop forwarding ─────────────────────────────────────────────────────

object OneHopTarget {
  def consume(name: String): Unit = ()
}

class OneHopForwarder {
  def run(): Unit = forward("one_hop")
  def forward(s: String): Unit = OneHopTarget.consume(s)
}

// ── 3. Two-hop forwarding ─────────────────────────────────────────────────────

object TwoHopTarget {
  def consume(name: String): Unit = ()
}

class TwoHopForwarder {
  def run(): Unit = hop1("two_hop")
  def hop1(s: String): Unit = hop2(s)
  def hop2(s: String): Unit = TwoHopTarget.consume(s)
}

// ── 4. Multiple call sites ────────────────────────────────────────────────────

object MultiTarget {
  def consume(name: String): Unit = ()
}

class MultiCallerA {
  def run(): Unit = MultiTarget.consume("multi_a")
}

class MultiCallerB {
  def run(): Unit = MultiTarget.consume("multi_b")
}

// ── 5. Cycle guard ─────────────────────────────────────────────────────────────
//
// A and B forward to each other. No literal ever escapes the cycle, so
// resolve should return Set.empty without hanging.

object CycleChain {
  def methodA(s: String): Unit = methodB(s)
  def methodB(s: String): Unit = methodA(s)
}
