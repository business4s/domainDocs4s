package domaindocs4s.architecture.lineage

/** Renders a ScanResult to a JSON string suitable for the interactive viewer.
  *
  * The JSON format is designed to be consumed by a standalone HTML viewer and contains all information needed to render interactive architecture
  * diagrams at both method-level and class-level granularity.
  */
object JsonRenderer {

  def render(result: ScanResult, config: ClassLevelConfig = ClassLevelConfig()): String = {
    val sb = new StringBuilder
    sb.append("{\n")

    // Classes with methods
    sb.append("  \"classes\": [\n")
    val classEntries = result.classes.zipWithIndex
    for ((cls, ci) <- classEntries) {
      sb.append("    {\n")
      sb.append(s"""      "name": ${jsonStr(cls.name)},\n""")
      sb.append(s"""      "packageName": ${jsonStr(cls.packageName)},\n""")
      val displayName = result.classDisplayNames.getOrElse((cls.packageName, cls.name), cls.name)
      sb.append(s"""      "displayName": ${jsonStr(displayName)},\n""")
      val group = result.classGroups.get((cls.packageName, cls.name))
      sb.append(s"""      "group": ${group.fold("null")(jsonStr)},\n""")
      sb.append(s"""      "effectiveAccess": ${jsonStr(cls.effectiveAccess.toString)},\n""")
      sb.append("      \"methods\": [\n")
      val methodEntries = cls.methods.zipWithIndex
      for ((m, mi) <- methodEntries) {
        sb.append("        {\n")
        sb.append(s"""          "name": ${jsonStr(m.ref.methodName)},\n""")
        sb.append(s"""          "packageName": ${jsonStr(m.ref.packageName)},\n""")
        sb.append(s"""          "className": ${jsonStr(m.ref.className)},\n""")
        sb.append(s"""          "directAccess": ${jsonStr(m.directAccess.toString)},\n""")
        sb.append(s"""          "effectiveAccess": ${jsonStr(m.effectiveAccess.toString)}\n""")
        sb.append("        }")
        if (mi < methodEntries.size - 1) sb.append(",")
        sb.append("\n")
      }
      sb.append("      ]\n")
      sb.append("    }")
      if (ci < classEntries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  ],\n")

    // Call graph edges
    sb.append("  \"callGraph\": [\n")
    val callEntries = result.callGraph.zipWithIndex
    for ((edge, i) <- callEntries) {
      sb.append("    {\n")
      sb.append(s"""      "caller": ${renderMethodRef(edge.caller)},\n""")
      sb.append(s"""      "callee": ${renderMethodRef(edge.callee)}\n""")
      sb.append("    }")
      if (i < callEntries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  ],\n")

    // Integrations (code-scanned)
    sb.append("  \"integrations\": [\n")
    val intEntries = result.integrations.zipWithIndex
    for ((integ, i) <- intEntries) {
      renderIntegration(sb, integ, i < intEntries.size - 1)
    }
    sb.append("  ],\n")

    // Resources (merged/deduplicated)
    sb.append("  \"resources\": [\n")
    val resEntries = result.resources.zipWithIndex
    for ((res, i) <- resEntries) {
      sb.append("    {\n")
      sb.append(s"""      "key": ${jsonStr(res.resourceId.key)},\n""")
      sb.append(s"""      "target": ${jsonStr(res.target)},\n""")
      sb.append(s"""      "resourceType": ${jsonStr(res.resourceType.value)},\n""")
      sb.append(s"""      "segments": [${renderSegments(res.resourceId)}],\n""")
      sb.append("      \"discoveries\": [\n")
      val discEntries = res.discoveries.zipWithIndex
      for ((d, di) <- discEntries) {
        sb.append("        {\n")
        sb.append(s"""          "method": ${renderMethodRef(d.method)},\n""")
        sb.append(s"""          "accessType": ${jsonStr(d.accessType.toString)},\n""")
        sb.append(s"""          "scanner": ${jsonStr(d.scanner)},\n""")
        sb.append(s"""          "evidence": ${jsonStr(d.evidence)}\n""")
        sb.append("        }")
        if (di < discEntries.size - 1) sb.append(",")
        sb.append("\n")
      }
      sb.append("      ]\n")
      sb.append("    }")
      if (i < resEntries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  ],\n")

    // Resource dependencies
    sb.append("  \"resourceDependencies\": [\n")
    val depEntries = result.resourceDependencies.zipWithIndex
    for ((dep, i) <- depEntries) {
      sb.append("    {\n")
      sb.append(s"""      "from": ${jsonStr(dep.from.key)},\n""")
      sb.append(s"""      "to": ${jsonStr(dep.to.key)},\n""")
      sb.append(s"""      "resourceType": ${jsonStr(dep.from.resourceType.value)},\n""")
      sb.append(s"""      "label": ${jsonStr(dep.label)}\n""")
      sb.append("    }")
      if (i < depEntries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  ],\n")

    // Lineage chains
    sb.append("  \"lineageChains\": [\n")
    val chainEntries = result.lineageChains.zipWithIndex
    for ((chain, i) <- chainEntries) {
      sb.append("    {\n")
      sb.append(s"""      "entryPoint": ${renderMethodRef(chain.entryPoint)},\n""")
      sb.append(s"""      "path": [${chain.path.map(renderMethodRef).mkString(", ")}],\n""")
      sb.append(s"""      "integration": {\n""")
      sb.append(s"""        "target": ${jsonStr(chain.integration.target)},\n""")
      sb.append(s"""        "resourceType": ${jsonStr(chain.integration.resourceType.value)},\n""")
      sb.append(s"""        "resourceKey": ${jsonStr(chain.integration.resourceId.key)},\n""")
      sb.append(s"""        "accessType": ${jsonStr(chain.integration.accessType.toString)},\n""")
      sb.append(s"""        "scanner": ${jsonStr(chain.integration.scanner)},\n""")
      sb.append(s"""        "evidence": ${jsonStr(chain.integration.evidence)}\n""")
      sb.append("      }\n")
      sb.append("    }")
      if (i < chainEntries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  ],\n")

    // Views
    sb.append("  \"views\": [\n")
    val viewEntries = result.views.zipWithIndex
    for ((view, i) <- viewEntries) {
      sb.append("    {\n")
      sb.append(s"""      "name": ${jsonStr(view.name)},\n""")
      sb.append("      \"hiddenClasses\": [\n")
      val hiddenEntries = view.hiddenClasses.toList.sorted.zipWithIndex
      for (((pkg, cls), hi) <- hiddenEntries) {
        sb.append(s"""        {"packageName": ${jsonStr(pkg)}, "className": ${jsonStr(cls)}}""")
        if (hi < hiddenEntries.size - 1) sb.append(",")
        sb.append("\n")
      }
      sb.append("      ]\n")
      sb.append("    }")
      if (i < viewEntries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  ],\n")

    // Resource display config
    sb.append("  \"resourceDisplayConfig\": {\n")
    val dispEntries = config.resourceDisplay.toList.sortBy(_._1).zipWithIndex
    for (((rtype, disp), i) <- dispEntries) {
      sb.append(s"""    ${jsonStr(rtype.value)}: {"containerLabel": ${disp.containerLabel.fold("null")(jsonStr)}, "foldAtLevel": ${disp.foldAtLevel.fold("null")(jsonStr)}}""")
      if (i < dispEntries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  },\n")

    // Segment labels (display overrides for segment values)
    sb.append("  \"segmentLabels\": {\n")
    val labelEntries = result.segmentLabels.toList.sorted.zipWithIndex
    for (((key, label), i) <- labelEntries) {
      sb.append(s"""    ${jsonStr(key)}: ${jsonStr(label)}""")
      if (i < labelEntries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  }\n")

    sb.append("}")
    sb.toString()
  }

  /** Render multiple service scan results into a single JSON for the multi-service viewer.
    * Format: {"services": [{"name": "...", "data": {...}}, ...]}
    */
  def renderMultiService(services: List[(String, ScanResult, ClassLevelConfig)]): String = {
    val sb = new StringBuilder
    sb.append("{\n  \"services\": [\n")
    val entries = services.zipWithIndex
    for (((name, result, config), i) <- entries) {
      sb.append(s"""    {"name": ${jsonStr(name)}, "data": """)
      sb.append(render(result, config))
      sb.append("}")
      if (i < entries.size - 1) sb.append(",")
      sb.append("\n")
    }
    sb.append("  ]\n}")
    sb.toString()
  }

  /** Render to a self-contained HTML file with the viewer embedded. */
  def renderHtml(result: ScanResult, config: ClassLevelConfig = ClassLevelConfig(), viewerJs: String = ""): String = {
    val json = render(result, config)
    s"""<!DOCTYPE html>
       |<html lang="en">
       |<head>
       |  <meta charset="UTF-8">
       |  <meta name="viewport" content="width=device-width, initial-scale=1.0">
       |  <title>Architecture Viewer</title>
       |  <style>
       |    html, body, #app { margin: 0; padding: 0; width: 100%; height: 100%; }
       |  </style>
       |</head>
       |<body>
       |  <div id="app"></div>
       |  <script type="application/json" id="lineage-data">
       |$json
       |  </script>
       |  <script type="module">
       |$viewerJs
       |  </script>
       |</body>
       |</html>""".stripMargin
  }

  private def renderSegments(rid: ResourceId): String =
    rid.segments.map((l, v) => s"""{"level": ${jsonStr(l)}, "value": ${jsonStr(v)}}""").mkString(", ")

  private def renderMethodRef(ref: MethodRef): String =
    s"""{"packageName": ${jsonStr(ref.packageName)}, "className": ${jsonStr(ref.className)}, "methodName": ${jsonStr(ref.methodName)}}"""

  private def renderIntegration(sb: StringBuilder, integ: DiscoveredIntegration, hasMore: Boolean): Unit = {
    sb.append("    {\n")
    sb.append(s"""      "method": ${renderMethodRef(integ.method)},\n""")
    sb.append(s"""      "accessType": ${jsonStr(integ.accessType.toString)},\n""")
    sb.append(s"""      "resourceType": ${jsonStr(integ.resourceType.value)},\n""")
    sb.append(s"""      "scanner": ${jsonStr(integ.scanner)},\n""")
    sb.append(s"""      "target": ${jsonStr(integ.target)},\n""")
    sb.append(s"""      "resourceKey": ${jsonStr(integ.resourceId.key)},\n""")
    sb.append(s"""      "evidence": ${jsonStr(integ.evidence)},\n""")
    sb.append(s"""      "segments": [${renderSegments(integ.resourceId)}]\n""")
    sb.append("    }")
    if (hasMore) sb.append(",")
    sb.append("\n")
  }

  private def jsonStr(s: String): String = {
    val sb = new StringBuilder("\"")
    for (c <- s) c match {
      case '"'  => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
      case _    => sb.append(c)
    }
    sb.append("\"")
    sb.toString()
  }
}
