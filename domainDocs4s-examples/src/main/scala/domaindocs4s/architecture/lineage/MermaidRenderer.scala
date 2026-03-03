package domaindocs4s.architecture.lineage

import java.nio.charset.StandardCharsets
import java.util.Base64

object MermaidRenderer {

  def render(result: ScanResult): String = {
    val sb = new StringBuilder
    sb.append("flowchart LR\n")

    // Collect integration targets (DB tables, etc.)
    val targets = result.integrations.map(i => (i.target, i.integrationType)).distinct

    // Class subgraphs with methods
    for (cls <- result.classes) {
      val methods = cls.methods.filter(m => m.calls.nonEmpty || m.integrations.nonEmpty || isCalledByOthers(m.ref, result))
      if (methods.nonEmpty) {
        sb.append(s"  subgraph ${cls.name}\n")
        for (m <- methods) {
          val id = nodeId(m.ref)
          sb.append(s"""    $id["${m.ref.methodName}"]\n""")
        }
        sb.append("  end\n")
      }
    }

    sb.append("\n")

    // Integration target nodes (DB tables as cylinders)
    for ((target, itype) <- targets) {
      sb.append(s"""  ${targetNodeId(target)}[("${target}\n[$itype]")]\n""")
    }

    sb.append("\n")

    // Call graph edges
    for (edge <- result.callGraph) {
      val from = nodeId(edge.caller)
      val to = nodeId(edge.callee)
      sb.append(s"  $from --> $to\n")
    }

    sb.append("\n")

    // Integration edges (method -> target)
    for (i <- result.integrations) {
      val from = nodeId(i.method)
      val to = targetNodeId(i.target)
      i.accessType match {
        case DataAccessType.Read  => sb.append(s"""  $from -.->|Read| $to\n""")
        case DataAccessType.Write => sb.append(s"""  $from ==>|Write| $to\n""")
        case _                    => sb.append(s"""  $from -->|${i.accessType}| $to\n""")
      }
    }

    // Styling
    sb.append("\n")
    sb.append("  classDef readNode fill:#d4edda,stroke:#28a745\n")
    sb.append("  classDef writeNode fill:#f8d7da,stroke:#dc3545\n")
    sb.append("  classDef rwNode fill:#fff3cd,stroke:#ffc107\n")
    sb.append("  classDef dbNode fill:#d1ecf1,stroke:#17a2b8\n")

    for (m <- result.allMethods if m.effectiveAccess != DataAccessType.Pure) {
      val cls = m.effectiveAccess match {
        case DataAccessType.Read      => "readNode"
        case DataAccessType.Write     => "writeNode"
        case DataAccessType.ReadWrite => "rwNode"
        case _                        => ""
      }
      if (cls.nonEmpty) sb.append(s"  class ${nodeId(m.ref)} $cls\n")
    }

    for ((target, _) <- targets)
      sb.append(s"  class ${targetNodeId(target)} dbNode\n")

    sb.toString()
  }

  def toViewUrl(mermaidCode: String): String = {
    val json    = s"""{"code":${escapeJsonString(mermaidCode)}}"""
    val encoded = Base64.getEncoder.encodeToString(json.getBytes(StandardCharsets.UTF_8))
    val base64url = encoded
      .replace('+', '-')
      .replace('/', '_')
    s"https://mermaid.live/edit#base64:$base64url"
  }

  private def nodeId(ref: MethodRef): String =
    s"${ref.className}_${ref.methodName}".replaceAll("[^a-zA-Z0-9_]", "_")

  private def targetNodeId(target: String): String =
    s"db_${target.replaceAll("[^a-zA-Z0-9_]", "_")}"

  private def isCalledByOthers(ref: MethodRef, result: ScanResult): Boolean =
    result.callGraph.exists(_.callee == ref)

  private def escapeJsonString(s: String): String = {
    val sb = new StringBuilder("\"")
    for (c <- s) c match {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case _    => sb.append(c)
    }
    sb.append("\"")
    sb.toString()
  }
}
