package domaindocs4s.architecture.lineage

import java.nio.charset.StandardCharsets
import java.util.Base64

object MermaidRenderer {

  /** Render with arrows following call/access direction (method → target for both reads and writes). */
  def render(result: ScanResult): String =
    renderInternal(result, dataFlow = false)

  /** Render with arrows following data flow (target → method for reads, method → target for writes). */
  def renderDataFlow(result: ScanResult): String =
    renderInternal(result, dataFlow = true)

  private def renderInternal(result: ScanResult, dataFlow: Boolean): String = {
    val sb = new StringBuilder
    sb.append("flowchart LR\n")

    // Class subgraphs with methods
    val classNames = result.classes.map(_.name).toSet
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

    // Integration target nodes — grouped by subgraph when group is set
    val integrationsByGroup = result.integrations
      .map(i => (i.target, i.integrationType, i.group))
      .distinct
      .groupBy(_._3)

    // Grouped targets in subgraphs
    for (case (Some(groupName), entries) <- integrationsByGroup) {
      // Disambiguate from class subgraphs that share the same name
      val safeId = s"ext_group_${groupName.replaceAll("[^a-zA-Z0-9_]", "_")}"
      val label = if (classNames.contains(groupName)) s"$groupName (ext)" else groupName
      sb.append(s"""  subgraph $safeId ["$label"]\n""")
      for ((target, itype, _) <- entries) {
        renderTargetNode(sb, targetNodeId(target), target.split("/").last, itype, indent = "    ")
      }
      sb.append("  end\n")
    }

    // Ungrouped targets as standalone nodes
    for ((target, itype, _) <- integrationsByGroup.getOrElse(None, Nil)) {
      renderTargetNode(sb, targetNodeId(target), target, itype, indent = "  ")
    }

    sb.append("\n")

    // Call graph edges
    for (edge <- result.callGraph) {
      val from = nodeId(edge.caller)
      val to = nodeId(edge.callee)
      sb.append(s"  $from --> $to\n")
    }

    sb.append("\n")

    // Integration edges
    for (i <- result.integrations) {
      val methodNode = nodeId(i.method)
      val targetNode = targetNodeId(i.target)
      (i.accessType, dataFlow) match {
        case (DataAccessType.Read, false) => sb.append(s"""  $methodNode -.->|Read| $targetNode\n""")
        case (DataAccessType.Read, true)  => sb.append(s"""  $targetNode -.->|Read| $methodNode\n""")
        case (DataAccessType.Write, _)    => sb.append(s"""  $methodNode ==>|Write| $targetNode\n""")
        case _                            => sb.append(s"""  $methodNode -->|${i.accessType}| $targetNode\n""")
      }
    }

    // Styling
    sb.append("\n")
    sb.append("  classDef readNode fill:#d4edda,stroke:#28a745\n")
    sb.append("  classDef writeNode fill:#f8d7da,stroke:#dc3545\n")
    sb.append("  classDef rwNode fill:#fff3cd,stroke:#ffc107\n")
    sb.append("  classDef dbNode fill:#d1ecf1,stroke:#17a2b8\n")
    sb.append("  classDef grpcNode fill:#e8daef,stroke:#8e44ad\n")

    for (m <- result.allMethods if m.effectiveAccess != DataAccessType.Pure) {
      val cls = m.effectiveAccess match {
        case DataAccessType.Read      => "readNode"
        case DataAccessType.Write     => "writeNode"
        case DataAccessType.ReadWrite => "rwNode"
        case _                        => ""
      }
      if (cls.nonEmpty) sb.append(s"  class ${nodeId(m.ref)} $cls\n")
    }

    for ((_, entries) <- integrationsByGroup; (target, itype, _) <- entries) {
      val style = if (itype == "grpc") "grpcNode" else "dbNode"
      sb.append(s"  class ${targetNodeId(target)} $style\n")
    }

    sb.toString()
  }

  /** Render a single integration target node into the StringBuilder. */
  private def renderTargetNode(sb: StringBuilder, id: String, label: String, itype: String, indent: String): Unit =
    itype match {
      case "grpc" => sb.append(s"""$indent$id{{"${label}\n[$itype]"}}\n""")
      case _      => sb.append(s"""$indent$id[("${label}\n[$itype]")]\n""")
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
    s"ext_${target.replaceAll("[^a-zA-Z0-9_]", "_")}"

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
