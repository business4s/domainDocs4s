package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*

class MermaidRendererTest extends AnyFreeSpec {

  private val pkg = "com.example"

  "MermaidRenderer" - {

    "escapes HTML characters in target node labels" in {
      val method = ScannedMethod(
        ref = MethodRef(pkg, "MyClass", "doStuff"),
        directAccess = DataAccessType.Read,
        effectiveAccess = DataAccessType.Read,
        calls = Nil,
        integrations = List(
          DiscoveredIntegration(
            method = MethodRef(pkg, "MyClass", "doStuff"),
            accessType = DataAccessType.Read,
            resourceType = ResourceType.Database,
            scanner = "test",
            target = "<unresolved:SomeTable>",
            evidence = "test",
          ),
        ),
      )
      val result = ScanResult(
        classes = List(ScannedClass(name = "MyClass", packageName = pkg, methods = List(method))),
        callGraph = Nil,
        integrations = method.integrations,
        lineageChains = Nil,
      )

      val mermaid = MermaidRenderer.render(result)
      // The < and > should be escaped so Mermaid renders them as text
      mermaid should include("&lt;unresolved:SomeTable&gt;")
      mermaid should not include "<unresolved:SomeTable>"
    }

    "class-level rendering filters out self-referencing edges" in {
      val methodA = ScannedMethod(
        ref = MethodRef(pkg, "Service", "methodA"),
        directAccess = DataAccessType.Pure,
        effectiveAccess = DataAccessType.Pure,
        calls = List(MethodRef(pkg, "Service", "methodB")),
        integrations = Nil,
      )
      val methodB = ScannedMethod(
        ref = MethodRef(pkg, "Service", "methodB"),
        directAccess = DataAccessType.Read,
        effectiveAccess = DataAccessType.Read,
        calls = Nil,
        integrations = List(
          DiscoveredIntegration(
            method = MethodRef(pkg, "Service", "methodB"),
            accessType = DataAccessType.Read,
            resourceType = ResourceType.Database,
            scanner = "test",
            target = "my_table",
            evidence = "test",
          ),
        ),
      )
      val result  = ScanResult(
        classes = List(ScannedClass(name = "Service", packageName = pkg, methods = List(methodA, methodB))),
        callGraph = List(CallEdge(methodA.ref, methodB.ref)),
        integrations = methodA.integrations ++ methodB.integrations,
        lineageChains = Nil,
      )

      val mermaid   = MermaidRenderer.renderClassLevel(result)
      // The class-level diagram should NOT have a self-edge for Service → Service
      // Count arrows — there should be no "cls_X_Service --> cls_X_Service" line
      val selfEdges = mermaid.linesIterator.count { line =>
        val trimmed = line.trim
        trimmed.contains("-->") && {
          val parts = trimmed.split("-->").map(_.trim)
          parts.length == 2 && parts(0) == parts(1)
        }
      }
      selfEdges shouldBe 0
    }
  }
}
