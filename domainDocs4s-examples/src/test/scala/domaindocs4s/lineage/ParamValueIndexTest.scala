package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class ParamValueIndexTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val paramPkg = "domaindocs4s.architecture.lineage.example.param"
  private val index    = ParamValueIndex.build(List(paramPkg))

  "ParamValueIndex" - {

    "resolves a direct string literal arg" in {
      index.resolve(paramPkg, "DirectTarget", "consume", 0) shouldBe Set("direct_value")
    }

    "resolves a literal forwarded through 1 hop" in {
      // OneHopForwarder.run("one_hop") -> forward(s) -> OneHopTarget.consume(s)
      index.resolve(paramPkg, "OneHopTarget", "consume", 0) shouldBe Set("one_hop")
    }

    "resolves a literal forwarded through 2 hops" in {
      // TwoHopForwarder.run("two_hop") -> hop1(s) -> hop2(s) -> TwoHopTarget.consume(s)
      index.resolve(paramPkg, "TwoHopTarget", "consume", 0) shouldBe Set("two_hop")
    }

    "collects literals from multiple independent call sites" in {
      // MultiCallerA.run("multi_a") and MultiCallerB.run("multi_b") both call MultiTarget.consume
      val result = index.resolve(paramPkg, "MultiTarget", "consume", 0)
      result shouldBe Set("multi_a", "multi_b")
    }

    "does not hang on cycles and returns empty when no literal escapes" in {
      // CycleChain.methodA <-> methodB — no literal ever enters the cycle
      val result = index.resolve(paramPkg, "CycleChain", "methodA", 0)
      result shouldBe empty
    }
  }
}
