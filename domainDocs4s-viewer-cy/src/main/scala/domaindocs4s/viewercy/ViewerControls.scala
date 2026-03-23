package domaindocs4s.viewercy

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import CytoscapeFacade.*

/** Laminar component: initializes Cytoscape and provides layout/fold/focus controls. */
object ViewerControls {

  def component(graph: GraphBuilder.GraphData, data: LineageData, defaultView: View, codeViews: List[View]): HtmlElement = {
    val cyRef        = Var[Option[CyInstance]](None)
    val activeLayout    = Var("elk-layered")
    val activeCurve     = Var("taxi")
    val focusedNode     = Var[Option[String]](None)
    val dataFlow     = Var(true)
    val searchQuery  = Var("")
    val activeFilter = Var("none")

    // View management
    val builtInViews    = if (codeViews.nonEmpty) codeViews else List(defaultView)
    val savedViews      = Var(builtInViews ++ ViewStore.loadAll())
    val activeViewName  = Var(defaultView.name)
    val selectedNodeIds = Var(Set.empty[String])
    val viewMode        = Var("cytoscape") // "cytoscape" or "mermaid"

    div(
      onMountCallback { _ =>
        registerExtensions()
        val cy = initCytoscape(graph, data, activeLayout, focusedNode, selectedNodeIds, savedViews, activeViewName)
        cyRef.set(Some(cy))
        applyView(cy, defaultView)
        flipEdges(cy, toDataFlow = true)
        runLayout(cy, "elk-layered")
      },
      // Renderer toggle
      div(
        span("Renderer: "),
        button(
          "Cytoscape",
          cls <-- viewMode.signal.map(m => if (m == "cytoscape") "active" else ""),
          onClick --> { _ =>
            viewMode.set("cytoscape")
            switchToCytoscape()
          },
        ),
        button(
          "Mermaid",
          cls <-- viewMode.signal.map(m => if (m == "mermaid") "active" else ""),
          onClick --> { _ =>
            viewMode.set("mermaid")
            val visibleIds = currentVisibleClassIds(cyRef.now())
            switchToMermaid(data, visibleIds, dataFlow.now())
          },
        ),
      ),
      // Cytoscape-only controls (hidden when Mermaid is active)
      div(
        display <-- viewMode.signal.map(m => if (m == "cytoscape") "contents" else "none"),
      // Layout buttons
      div(
        span("Layout: "),
        layoutButtons.map { case (id, label) =>
          button(
            label,
            cls <-- activeLayout.signal.map(a => if (a == id) "active" else ""),
            onClick --> { _ =>
              cyRef.now().foreach { cy =>
                runLayout(cy, id)
                activeLayout.set(id)
              }
            },
          )
        },
        span(" | "),
        button(
          "Fold All",
          onClick --> { _ =>
            cyRef.now().foreach { cy =>
              collapseAll(cy, graph.groupIds)
              runLayout(cy, activeLayout.now())
            }
          },
        ),
        button(
          "Unfold All",
          onClick --> { _ =>
            cyRef.now().foreach { cy =>
              expandAll(cy, graph.groupIds)
              runLayout(cy, activeLayout.now())
            }
          },
        ),
        button(
          "Re-layout",
          onClick --> { _ =>
            cyRef.now().foreach { cy =>
              runLayout(cy, activeLayout.now())
            }
          },
        ),
      ),
      div(
        span("Edges: "),
        curveStyles.map { case (id, label) =>
          button(
            label,
            cls <-- activeCurve.signal.map(a => if (a == id) "active" else ""),
            onClick --> { _ =>
              cyRef.now().foreach { cy =>
                setCurveStyle(cy, id)
                activeCurve.set(id)
              }
            },
          )
        },
        span(" | "),
        span("Arrows: "),
        button(
          child.text <-- dataFlow.signal.map(df => if (df) "Call Direction" else "Call Direction"),
          cls <-- dataFlow.signal.map(df => if (!df) "active" else ""),
          onClick --> { _ =>
            if (dataFlow.now()) {
              cyRef.now().foreach(cy => flipEdges(cy, toDataFlow = false))
              dataFlow.set(false)
            }
          },
        ),
        button(
          "Data Flow",
          cls <-- dataFlow.signal.map(df => if (df) "active" else ""),
          onClick --> { _ =>
            if (!dataFlow.now()) {
              cyRef.now().foreach(cy => flipEdges(cy, toDataFlow = true))
              dataFlow.set(true)
            }
          },
        ),
        // Focus indicator + clear button
        child <-- focusedNode.signal.map {
        case Some(nodeId) =>
          span(
            " | Focused: ",
            b(nodeId),
            " ",
            button("Clear Focus", onClick --> { _ =>
              cyRef.now().foreach(cy => clearFocus(cy))
              focusedNode.set(None)
            }),
          )
        case None => emptyNode
        },
      ),
      // Separator
      div(cls := "separator"),
      // Search by name
      span("Search: "),
      input(
        typ := "text",
        placeholder := "Filter by name...",
        controlled(
          value <-- searchQuery.signal,
          onInput.mapToValue --> { v =>
            searchQuery.set(v)
            cyRef.now().foreach(cy => applyFilter(cy, activeFilter.now(), v))
          },
        ),
      ),
      button("✕", onClick --> { _ =>
        searchQuery.set("")
        cyRef.now().foreach(cy => applyFilter(cy, activeFilter.now(), ""))
      }),
      span(" | Filter: "),
      filterButtons.map { case (id, label) =>
        button(
          label,
          cls <-- activeFilter.signal.map(a => if (a == id) "active" else ""),
          onClick --> { _ =>
            val newFilter = if (activeFilter.now() == id) "none" else id
            activeFilter.set(newFilter)
            cyRef.now().foreach(cy => applyFilter(cy, newFilter, searchQuery.now()))
          },
        )
      },
      // Separator
      div(cls := "separator"),
      // View management
      span("View: "),
      child <-- savedViews.signal.combineWith(activeViewName.signal).map { case (views, active) =>
        span(
          views.map { v =>
            button(
              v.name,
              cls := (if (v.name == active) "active" else ""),
              onClick --> { _ =>
                cyRef.now().foreach { cy =>
                  applyView(cy, v)
                  activeViewName.set(v.name)
                  runLayout(cy, activeLayout.now())
                }
              },
            )
          },
        )
      },
      span(" | "),
      // Selection info + hide/show actions
      child <-- selectedNodeIds.signal.map { sel =>
        if (sel.isEmpty) span(styleAttr := "color:#888;", "Click nodes to select, Shift+click for multi")
        else span(
          b(s"${sel.size} selected"),
          " ",
          button("Hide selected", onClick --> { _ =>
            cyRef.now().foreach { cy =>
              hideNodes(cy, sel)
              selectedNodeIds.set(Set.empty)
              // Save as current view
              val currentView = getCurrentView(cy, activeViewName.now())
              updateSavedView(savedViews, currentView)
              runLayout(cy, activeLayout.now())
            }
          }),
          " ",
          button("Clear selection", onClick --> { _ =>
            cyRef.now().foreach(cy => clearSelection(cy))
            selectedNodeIds.set(Set.empty)
          }),
        )
      },
      span(" | "),
      button("Add classes...", onClick --> { _ =>
        cyRef.now().foreach(cy => showAddClassesPanel(cy, data, activeLayout))
      }),
      button("Show hidden", onClick --> { _ =>
        cyRef.now().foreach(cy => showHiddenPanel(cy, savedViews, activeViewName, activeLayout))
      }),
      button("Save view as...", onClick --> { _ =>
        val name = dom.window.prompt("View name:", "My View")
        if (name != null && name.nonEmpty) {
          cyRef.now().foreach { cy =>
            val v = getCurrentView(cy, name)
            val views = savedViews.now().filterNot(_.name == name) :+ v
            savedViews.set(views)
            activeViewName.set(name)
            ViewStore.saveAll(views.filterNot(_.name == defaultView.name))
          }
        }
      }),
      button("Delete view", onClick --> { _ =>
        val active = activeViewName.now()
        if (active != defaultView.name && active != "All") {
          val views = savedViews.now().filterNot(_.name == active)
          savedViews.set(views)
          activeViewName.set(defaultView.name)
          ViewStore.saveAll(views.filterNot(_.name == defaultView.name))
          cyRef.now().foreach { cy =>
            applyView(cy, defaultView)
            runLayout(cy, activeLayout.now())
          }
        }
      }),
      ), // end Cytoscape-only controls wrapper
    )
  }

  private val layoutButtons: List[(String, String)] = List(
    "elk-layered" -> "ELK Layered",
    "elk-routed"  -> "ELK Routed",
    "elk-force"   -> "ELK Force",
    "dagre"       -> "Dagre",
    "fcose"       -> "fCoSE",
    "cose-bilkent" -> "CoSE Bilkent",
    "cose"        -> "CoSE (built-in)",
    "breadthfirst" -> "Breadthfirst",
    "circle"      -> "Circle",
  )

  private val curveStyles: List[(String, String)] = List(
    "taxi"            -> "Taxi",
    "offset-taxi"     -> "Taxi (offset)",
    "bezier"          -> "Bezier",
    "segments"        -> "Segments",
    "straight"        -> "Straight",
    "unbundled-bezier" -> "Unbundled",
  )

  private val filterButtons: List[(String, String)] = List(
    "no-writes"      -> "No Writes",
    "no-reads"       -> "No Reads",
    "no-connections" -> "No Connections",
    "writes-only"    -> "Writes Only",
    "reads-only"     -> "Reads Only",
    "kafka"          -> "Kafka",
    "grpc"           -> "gRPC",
    "db"             -> "Database",
    "s3"             -> "S3",
  )

  /** Apply search query + filter, dimming non-matching elements. */
  private def applyFilter(cy: CyInstance, filterId: String, query: String): Unit = {
    val _1 = cy.elements().removeClass("unfocused")
    val lowerQuery = query.toLowerCase.trim

    // Collect matching node IDs
    val matchingNodeIds = scala.collection.mutable.Set[String]()
    cy.nodes().forEach { (node: CyElement) =>
      val nodeType = node.data("nodeType")
      if (js.isUndefined(nodeType) || nodeType == null) {
        // skip
      } else {
        val nt = nodeType.asInstanceOf[String]
        if (nt == "classGroup" || nt == "resourceGroup") {
          // groups are included if any child matches
        } else {
          val nameMatch = if (lowerQuery.isEmpty) true else {
            val label = node.data("label")
            if (js.isUndefined(label) || label == null) false
            else label.asInstanceOf[String].toLowerCase.contains(lowerQuery)
          }
          val filterMatch = matchesFilter(cy, node, filterId)
          if (nameMatch && filterMatch) matchingNodeIds += node.id()
        }
      }
    }

    // If no filter/search active, show everything
    if (lowerQuery.isEmpty && filterId == "none") return

    // Include parent groups of matching nodes
    val withParents = scala.collection.mutable.Set[String]()
    withParents ++= matchingNodeIds
    matchingNodeIds.foreach { nid =>
      val p = cy.getElementById(nid).data("parent")
      if (!js.isUndefined(p) && p != null) withParents += p.asInstanceOf[String]
    }

    // Dim non-matching nodes and their edges
    cy.nodes().forEach { (node: CyElement) =>
      if (!withParents.contains(node.id())) {
        val _a = node.addClass("unfocused")
      }
    }
    cy.edges().forEach { (edge: CyElement) =>
      val src = edge.data("source").asInstanceOf[String]
      val tgt = edge.data("target").asInstanceOf[String]
      if (!matchingNodeIds.contains(src) && !matchingNodeIds.contains(tgt)) {
        val _a = edge.addClass("unfocused")
      }
    }
  }

  private def matchesFilter(cy: CyInstance, node: CyElement, filterId: String): Boolean = {
    filterId match {
      case "none" => true
      case "no-writes" =>
        val access = node.data("accessType")
        if (js.isUndefined(access) || access == null) true
        else {
          val a = access.asInstanceOf[String]
          a != "Write" && a != "ReadWrite"
        }
      case "no-reads" =>
        val access = node.data("accessType")
        if (js.isUndefined(access) || access == null) true
        else {
          val a = access.asInstanceOf[String]
          a != "Read" && a != "ReadWrite"
        }
      case "no-connections" =>
        node.connectedEdges().length == 0
      case "writes-only" =>
        val access = node.data("accessType")
        if (js.isUndefined(access) || access == null) false
        else {
          val a = access.asInstanceOf[String]
          a == "Write" || a == "ReadWrite"
        }
      case "reads-only" =>
        val access = node.data("accessType")
        if (js.isUndefined(access) || access == null) false
        else {
          val a = access.asInstanceOf[String]
          a == "Read" || a == "ReadWrite"
        }
      case "kafka" | "grpc" | "s3" => matchesResourceType(cy, node, filterId)
      case "db" => matchesResourceType(cy, node, "database")
      case _ => true
    }
  }

  /** Check if a node is a resource of the given type, or a class connected to such a resource. */
  private def matchesResourceType(cy: CyInstance, node: CyElement, rtype: String): Boolean = {
    val nt = node.data("nodeType").asInstanceOf[String]
    if (nt == "resource") {
      val rt = node.data("resourceType")
      !js.isUndefined(rt) && rt != null && rt.asInstanceOf[String] == rtype
    } else if (nt == "class") {
      // Match class nodes that have integration edges to resources of this type
      var found = false
      node.connectedEdges().forEach { (edge: CyElement) =>
        if (!found) {
          val et = edge.data("edgeType")
          if (!js.isUndefined(et) && et != null && et.asInstanceOf[String] == "integration") {
            val src = edge.data("source").asInstanceOf[String]
            val tgt = edge.data("target").asInstanceOf[String]
            val otherId = if (src == node.id()) tgt else src
            val other = cy.getElementById(otherId)
            val otherRt = other.data("resourceType")
            if (!js.isUndefined(otherRt) && otherRt != null && otherRt.asInstanceOf[String] == rtype) {
              found = true
            }
          }
        }
      }
      found
    } else false
  }

  /** Find all nodes transitively reachable from a starting node (following edge direction). */
  private def showReachable(cy: CyInstance, startId: String, focusedNode: Var[Option[String]]): Unit = {
    val reachable = scala.collection.mutable.Set[String](startId)
    val queue = scala.collection.mutable.Queue[String](startId)

    while (queue.nonEmpty) {
      val current = queue.dequeue()
      cy.getElementById(current).connectedEdges().forEach { (edge: CyElement) =>
        val src = edge.data("source").asInstanceOf[String]
        val tgt = edge.data("target").asInstanceOf[String]
        // Follow outgoing edges from current node
        if (src == current && reachable.add(tgt)) {
          queue.enqueue(tgt)
        }
      }
    }

    // Include parent groups
    val withParents = scala.collection.mutable.Set[String]()
    withParents ++= reachable
    reachable.foreach { nid =>
      val p = cy.getElementById(nid).data("parent")
      if (!js.isUndefined(p) && p != null) withParents += p.asInstanceOf[String]
    }

    val _1 = cy.elements().addClass("unfocused")
    cy.nodes().forEach { (node: CyElement) =>
      if (withParents.contains(node.id())) {
        val _a = node.removeClass("unfocused")
      }
    }
    cy.edges().forEach { (edge: CyElement) =>
      val src = edge.data("source").asInstanceOf[String]
      val tgt = edge.data("target").asInstanceOf[String]
      if (reachable.contains(src) && reachable.contains(tgt)) {
        val _a = edge.removeClass("unfocused")
      }
    }

    val label = cy.getElementById(startId).data("label").asInstanceOf[String]
    focusedNode.set(Some(s"$label (reachable)"))
  }

  private def runLayout(cy: CyInstance, layoutId: String): Unit = {
    val opts = layoutId match {
      case "elk-layered" => js.Dynamic.literal(
        name = "elk",
        animate = false,
        elk = js.Dynamic.literal(
          algorithm = "layered",
          `elk.direction` = "RIGHT",
          `elk.spacing.nodeNode` = "40",
          `elk.layered.spacing.nodeNodeBetweenLayers` = "80",
          `elk.hierarchyHandling` = "INCLUDE_CHILDREN",
          `elk.layered.crossingMinimization.strategy` = "LAYER_SWEEP",
        ),
      )
      case "elk-routed" => js.Dynamic.literal(
        name = "elk-routed",
        animate = false,
        elk = js.Dynamic.literal(
          algorithm = "layered",
          `elk.direction` = "RIGHT",
          `elk.spacing.nodeNode` = "40",
          `elk.layered.spacing.nodeNodeBetweenLayers` = "80",
          `elk.hierarchyHandling` = "INCLUDE_CHILDREN",
          `elk.layered.crossingMinimization.strategy` = "LAYER_SWEEP",
          `elk.edgeRouting` = "ORTHOGONAL",
        ),
      )
      case "elk-force" => js.Dynamic.literal(
        name = "elk",
        animate = false,
        elk = js.Dynamic.literal(
          algorithm = "force",
          `elk.spacing.nodeNode` = "60",
          `elk.hierarchyHandling` = "INCLUDE_CHILDREN",
        ),
      )
      case "dagre" => js.Dynamic.literal(
        name = "dagre",
        animate = false,
        rankDir = "LR",
        nodeSep = 40,
        rankSep = 80,
        edgeSep = 20,
      )
      case "fcose" =>
        val nodeRepFn: js.Function1[js.Dynamic, js.Any] = (_: js.Dynamic) => 8000: js.Any
        val edgeElFn: js.Function1[js.Dynamic, js.Any] = (_: js.Dynamic) => 0.45: js.Any
        js.Dynamic.literal(
          name = "fcose",
          animate = false,
          quality = "proof",
          randomize = true,
          packComponents = true,
          nodeSeparation = 80,
          idealEdgeLength = 150,
          nodeRepulsion = nodeRepFn,
          edgeElasticity = edgeElFn,
        )
      case "cose-bilkent" => js.Dynamic.literal(
        name = "cose-bilkent",
        animate = false,
        quality = "proof",
        nodeDimensionsIncludeLabels = true,
        idealEdgeLength = 150,
        nodeRepulsion = 8000,
        edgeElasticity = 0.45,
      )
      case "cose" => js.Dynamic.literal(
        name = "cose",
        animate = false,
        nodeDimensionsIncludeLabels = true,
        idealEdgeLength = { (_: js.Dynamic) => 150: js.Any }: js.Function1[js.Dynamic, js.Any],
        nodeRepulsion = { (_: js.Dynamic) => 8000: js.Any }: js.Function1[js.Dynamic, js.Any],
      )
      case "breadthfirst" => js.Dynamic.literal(
        name = "breadthfirst",
        animate = false,
        directed = true,
        spacingFactor = 1.2,
        avoidOverlap = true,
      )
      case "circle" => js.Dynamic.literal(
        name = "circle",
        animate = false,
        avoidOverlap = true,
        spacingFactor = 1.5,
      )
      case _ => js.Dynamic.literal(name = layoutId, animate = false)
    }

    val layout = cy.layout(opts.asInstanceOf[js.Object])
    val _ = layout.run()
  }

  private def initCytoscape(
      graph: GraphBuilder.GraphData,
      data: LineageData,
      activeLayout: Var[String],
      focusedNode: Var[Option[String]],
      selectedNodeIds: Var[Set[String]],
      savedViews: Var[List[View]],
      activeViewName: Var[String],
  ): CyInstance = {
    val container = dom.document.getElementById("cy")

    val cy = cytoscape(js.Dynamic.literal(
      container = container,
      elements = graph.elements,
      style = cytoscapeStyle,
      layout = js.Dynamic.literal(`type` = "preset"),
      minZoom = 0.05,
      maxZoom = 3.0,
      wheelSensitivity = 0.3,
    ).asInstanceOf[js.Object])

    // Left-click on nodes: show details + handle selection
    cy.on("tap", "node", { (evt: js.Dynamic) =>
      val node = evt.target.asInstanceOf[CyElement]
      val originalEvent = evt.originalEvent
      val shiftKey = if (js.isUndefined(originalEvent) || originalEvent == null) false
                     else originalEvent.shiftKey.asInstanceOf[Boolean]

      if (shiftKey) {
        // Shift+click: toggle selection
        val id = node.id()
        val current = selectedNodeIds.now()
        if (current.contains(id)) {
          node.removeClass("selected")
          selectedNodeIds.set(current - id)
        } else {
          node.addClass("selected")
          selectedNodeIds.set(current + id)
        }
      } else {
        showDetails(cy, node, data, activeLayout)
      }
    }: js.Function1[js.Dynamic, Unit])

    // Right-click context menu on nodes
    cy.on("cxttap", "node", { (evt: js.Dynamic) =>
      val node = evt.target.asInstanceOf[CyElement]
      showContextMenu(cy, node, data, activeLayout, focusedNode, savedViews, activeViewName, evt)
    }: js.Function1[js.Dynamic, Unit])

    // Dismiss context menu on tap elsewhere (but not details pane)
    cy.on("tap", { (evt: js.Dynamic) =>
      removeContextMenu()
    }: js.Function1[js.Dynamic, Unit])

    cy
  }

  // ── Mermaid / Cytoscape switching ──

  private def currentVisibleClassIds(cyOpt: Option[CyInstance]): Set[String] = {
    cyOpt match {
      case None     => Set.empty
      case Some(cy) =>
        val ids = scala.collection.mutable.Set[String]()
        cy.nodes(".classNode").forEach { (node: CyElement) =>
          if (!node.hasClass("viewHidden")) ids += node.id()
        }
        ids.toSet
    }
  }

  private def switchToMermaid(data: LineageData, visibleClassIds: Set[String], dataFlow: Boolean): Unit = {
    val cyDiv      = dom.document.getElementById("cy")
    val mermaidDiv = dom.document.getElementById("mermaid-container")
    val output     = dom.document.getElementById("mermaid-output")
    if (cyDiv == null || mermaidDiv == null || output == null) return

    cyDiv.asInstanceOf[dom.html.Element].style.display = "none"
    mermaidDiv.asInstanceOf[dom.html.Element].style.display = "block"

    val mermaidCode = MermaidGenerator.renderClassLevel(data, visibleClassIds, dataFlow)
    output.innerHTML = ""
    output.textContent = mermaidCode

    val mermaidLib = js.Dynamic.global.window.__mermaid
    if (!js.isUndefined(mermaidLib) && mermaidLib != null) {
      val renderFn = mermaidLib.render
      if (!js.isUndefined(renderFn)) {
        val promise = mermaidLib.render("mermaid-graph", mermaidCode).asInstanceOf[js.Promise[js.Dynamic]]
        promise.`then` { (result: js.Dynamic) =>
          output.innerHTML = result.svg.asInstanceOf[String]
        }
      }
    }
  }

  private def switchToCytoscape(): Unit = {
    val cyDiv      = dom.document.getElementById("cy")
    val mermaidDiv = dom.document.getElementById("mermaid-container")
    if (cyDiv == null || mermaidDiv == null) return

    mermaidDiv.asInstanceOf[dom.html.Element].style.display = "none"
    cyDiv.asInstanceOf[dom.html.Element].style.display = "block"
  }

  // ── Focus logic ──

  private def focusOnNode(cy: CyInstance, nodeId: String, activeLayout: Var[String]): Unit = {
    val node = cy.getElementById(nodeId)

    // Find neighborhood: the node, its direct neighbors, edges between them, and parent groups
    val neighborhood = node.connectedEdges().asInstanceOf[js.Dynamic]
      .connectedNodes().asInstanceOf[CyCollection]
    val neighborIds = scala.collection.mutable.Set[String](nodeId)
    neighborhood.forEach((el: CyElement) => neighborIds += el.id())

    // Also include parent compound nodes of focused + neighbor nodes
    val parentIds = scala.collection.mutable.Set[String]()
    neighborIds.foreach { nid =>
      val n = cy.getElementById(nid)
      val p = n.data("parent")
      if (p != null && !js.isUndefined(p)) {
        parentIds += p.asInstanceOf[String]
      }
    }
    neighborIds ++= parentIds

    // Hide everything, then show the focused subgraph
    val _1 = cy.elements().addClass("unfocused")
    val _2 = cy.elements().forEach { (el: CyElement) =>
      if (el.isNode()) {
        if (neighborIds.contains(el.id())) {
          val _a = el.removeClass("unfocused")
        }
      } else if (el.isEdge()) {
        val src = el.data("source").asInstanceOf[String]
        val tgt = el.data("target").asInstanceOf[String]
        if (neighborIds.contains(src) && neighborIds.contains(tgt)) {
          val _a = el.removeClass("unfocused")
        }
      }
    }

    // Re-layout just the visible (non-unfocused) subgraph
    val focused = cy.elements().filter(":not(.unfocused)")
    val opts = js.Dynamic.literal(
      name = "fcose",
      animate = false,
      quality = "proof",
      randomize = true,
      nodeSeparation = 80,
      idealEdgeLength = 120,
    )
    val layout = focused.layout(opts.asInstanceOf[js.Object])
    val _3 = layout.run()
    cy.fit(40)
  }

  private def clearFocus(cy: CyInstance): Unit = {
    val _ = cy.elements().removeClass("unfocused")
  }

  // ── Edge direction flipping ──

  /** Flip edge directions for Read integration edges and resource dependency edges.
    * - Call direction (default): all edges class → resource
    * - Data flow: Read edges reversed (resource → class), Write stays class → resource
    */
  private def setCurveStyle(cy: CyInstance, curveStyle: String): Unit = {
    if (curveStyle == "offset-taxi") {
      val batchFn: js.Function0[Unit] = () => {
        cy.edges().asInstanceOf[js.Dynamic].style("curve-style", "taxi")
        cy.edges().forEach { (edge: CyElement) =>
          val srcOff = edge.data("sourceYOffset").asInstanceOf[Double]
          val tgtOff = edge.data("targetYOffset").asInstanceOf[Double]
          if (srcOff != 0.0 || tgtOff != 0.0) {
            edge.asInstanceOf[js.Dynamic].style(js.Dynamic.literal(
              `source-endpoint` = s"0 ${srcOff}px",
              `target-endpoint` = s"0 ${tgtOff}px",
            ))
          } else {
            edge.asInstanceOf[js.Dynamic].style(js.Dynamic.literal(
              `source-endpoint` = "outside-to-node",
              `target-endpoint` = "outside-to-node",
            ))
          }
        }
      }
      cy.batch(batchFn)
    } else {
      val batchFn: js.Function0[Unit] = () => {
        cy.edges().asInstanceOf[js.Dynamic].style("curve-style", curveStyle)
        // Reset endpoints to default
        cy.edges().forEach { (edge: CyElement) =>
          edge.asInstanceOf[js.Dynamic].style(js.Dynamic.literal(
            `source-endpoint` = "outside-to-node",
            `target-endpoint` = "outside-to-node",
          ))
        }
      }
      cy.batch(batchFn)
    }
  }

  private def flipEdges(cy: CyInstance, toDataFlow: Boolean): Unit = {
    val fn: js.Function0[Unit] = () => {
      // Flip Read integration edges
      val readEdges = cy.edges(".integrationEdge.Read")
      readEdges.forEach { (edge: CyElement) =>
        swapEdge(cy, edge)
      }
      // Resource dependency edges already represent data flow direction (source → dependent),
      // so they are not flipped.
    }
    cy.batch(fn)
  }

  /** Remove an edge and re-add it with source/target swapped. */
  private def swapEdge(cy: CyInstance, edge: CyElement): Unit = {
    val id     = edge.id()
    val src    = edge.data("source").asInstanceOf[String]
    val tgt    = edge.data("target").asInstanceOf[String]
    val label  = edge.data("label")
    val edgeType   = edge.data("edgeType")
    val accessType = edge.data("accessType")
    val metaGroup  = edge.data("metaGroup")

    val classStr = edge.classes().join(" ")

    val _1 = edge.asInstanceOf[CyCollection].remove()
    val newData = js.Dynamic.literal(
      id = id,
      source = tgt,
      target = src,
    )
    if (!js.isUndefined(label) && label != null) newData.label = label
    if (!js.isUndefined(edgeType) && edgeType != null) newData.edgeType = edgeType
    if (!js.isUndefined(accessType) && accessType != null) newData.accessType = accessType
    if (!js.isUndefined(metaGroup) && metaGroup != null) newData.metaGroup = metaGroup

    val newEdge = js.Dynamic.literal(
      group = "edges",
      data = newData,
    )
    if (classStr.nonEmpty) newEdge.classes = classStr
    val _2 = cy.add(newEdge.asInstanceOf[js.Object])
  }

  // ── Context menu ──

  private val contextMenuId = "cy-context-menu"

  private def removeContextMenu(): Unit = {
    Option(dom.document.getElementById(contextMenuId)).foreach(_.remove())
  }

  private def showContextMenu(
      cy: CyInstance,
      node: CyElement,
      data: LineageData,
      activeLayout: Var[String],
      focusedNode: Var[Option[String]],
      savedViews: Var[List[View]],
      activeViewName: Var[String],
      evt: js.Dynamic,
  ): Unit = {
    removeContextMenu()

    val renderedPos = evt.renderedPosition
    val x = renderedPos.x.asInstanceOf[Double]
    val y = renderedPos.y.asInstanceOf[Double]
    val label = node.data("label").asInstanceOf[String]

    val menu = dom.document.createElement("div")
    menu.id = contextMenuId
    menu.setAttribute("style",
      s"position:absolute;left:${x}px;top:${y}px;z-index:100;" +
      "background:white;border:1px solid #ccc;border-radius:6px;box-shadow:0 2px 8px rgba(0,0,0,0.2);" +
      "padding:4px 0;font-size:12px;min-width:160px;font-family:-apple-system,BlinkMacSystemFont,sans-serif;")

    val header = dom.document.createElement("div")
    header.setAttribute("style", "padding:6px 12px;font-weight:600;color:#333;border-bottom:1px solid #eee;")
    header.textContent = label
    menu.appendChild(header)

    // Fold/unfold for compound (group) nodes
    if (node.isParent()) {
      val isCollapsed = node.hasClass("collapsed")
      val foldItem = dom.document.createElement("div")
      foldItem.setAttribute("style", "padding:6px 12px;cursor:pointer;")
      foldItem.textContent = if (isCollapsed) "Unfold group" else "Fold group"
      foldItem.addEventListener("mouseenter", (_: dom.Event) => foldItem.setAttribute("style", "padding:6px 12px;cursor:pointer;background:#f0f0f0;"))
      foldItem.addEventListener("mouseleave", (_: dom.Event) => foldItem.setAttribute("style", "padding:6px 12px;cursor:pointer;"))
      foldItem.addEventListener("click", (_: dom.Event) => {
        removeContextMenu()
        if (isCollapsed) expandNode(cy, node.id())
        else collapseNode(cy, node.id())
        runLayout(cy, activeLayout.now())
      })
      menu.appendChild(foldItem)
    }

    def menuItem(text: String)(action: => Unit): Unit = {
      val item = dom.document.createElement("div")
      item.setAttribute("style", "padding:6px 12px;cursor:pointer;")
      item.textContent = text
      item.addEventListener("mouseenter", (_: dom.Event) => item.setAttribute("style", "padding:6px 12px;cursor:pointer;background:#f0f0f0;"))
      item.addEventListener("mouseleave", (_: dom.Event) => item.setAttribute("style", "padding:6px 12px;cursor:pointer;"))
      item.addEventListener("click", (_: dom.Event) => { removeContextMenu(); action })
      menu.appendChild(item)
    }

    val nt = node.data("nodeType")
    val nodeType = if (js.isUndefined(nt) || nt == null) "" else nt.asInstanceOf[String]

    // Expand/collapse folded resource nodes
    if (nodeType == "resource") {
      val foldedData = node.data("folded")
      val isFolded = !js.isUndefined(foldedData) && foldedData != null && foldedData.asInstanceOf[Boolean]
      if (isFolded) {
        if (node.hasClass("resourceExpanded")) {
          menuItem("Collapse resource") {
            collapseResource(cy, node.id())
            runLayout(cy, activeLayout.now())
          }
        } else {
          val cnt = node.data("foldedCount")
          val count = if (!js.isUndefined(cnt) && cnt != null) cnt.asInstanceOf[Int] else 0
          menuItem(s"Expand resource ($count items)") {
            expandResource(cy, node.id(), data)
            runLayout(cy, activeLayout.now())
          }
        }
      }
    }

    // Expand/collapse class into methods
    if (nodeType == "class") {
      if (node.hasClass("expanded")) {
        menuItem("Collapse to class") { collapseClass(cy, node.id()) }
      } else {
        val classInfo = data.classes.find(_.classId == node.id())
        if (classInfo.exists(_.methods.size > 1)) {
          menuItem("Expand to methods") { expandClass(cy, node.id(), data) }
        }
      }
    }

    menuItem("Focus on this node") {
      focusOnNode(cy, node.id(), activeLayout)
      focusedNode.set(Some(label))
    }

    menuItem("Show reachable from here") {
      showReachable(cy, node.id(), focusedNode)
    }

    menuItem("Clear focus") {
      clearFocus(cy)
      focusedNode.set(None)
    }

    // Hide options
    if (nodeType == "class" || nodeType == "resource") {
      menuItem("Hide this node") {
        hideNodes(cy, Set(node.id()))
        val v = getCurrentView(cy, activeViewName.now())
        updateSavedView(savedViews, v)
        runLayout(cy, activeLayout.now())
      }

      if (nodeType == "class") {
        val pkg = node.data("packageName")
        if (!js.isUndefined(pkg) && pkg != null) {
          val pkgStr = pkg.asInstanceOf[String]
          val shortPkg = pkgStr.split('.').takeRight(2).mkString(".")
          menuItem(s"Hide package ($shortPkg)") {
            val toHide = scala.collection.mutable.Set[String]()
            cy.nodes(".classNode").forEach { (n: CyElement) =>
              val p = n.data("packageName")
              if (!js.isUndefined(p) && p != null && p.asInstanceOf[String] == pkgStr) {
                toHide += n.id()
              }
            }
            hideNodes(cy, toHide.toSet)
            val v = getCurrentView(cy, activeViewName.now())
            updateSavedView(savedViews, v)
            runLayout(cy, activeLayout.now())
          }
        }
      }
    }

    dom.document.body.appendChild(menu)
  }

  // ── View management ──

  private def applyView(cy: CyInstance, view: View): Unit = {
    val fn: js.Function0[Unit] = () => {
      // Show all nodes first
      cy.nodes().forEach { (node: CyElement) =>
        node.removeClass("viewHidden")
        val _a = node.show()
      }
      cy.edges().forEach { (edge: CyElement) =>
        val _a = edge.show()
      }
      // Hide nodes in the view
      for (id <- view.hiddenNodeIds) {
        if (nodeExists(cy, id)) {
          val node = cy.getElementById(id)
          node.addClass("viewHidden")
          val _a = node.hide()
          // Also hide connected edges where both ends would be hidden
          node.connectedEdges().forEach { (edge: CyElement) =>
            val src = edge.data("source").asInstanceOf[String]
            val tgt = edge.data("target").asInstanceOf[String]
            val otherId = if (src == id) tgt else src
            if (view.hiddenNodeIds.contains(otherId)) {
              val _b = edge.hide()
            }
          }
        }
      }
      // Hide empty groups (all children hidden)
      cy.nodes(".compound").forEach { (group: CyElement) =>
        val children = group.children()
        var allHidden = true
        children.forEach { (child: CyElement) =>
          if (!child.hasClass("viewHidden")) allHidden = false
        }
        if (children.length > 0 && allHidden) {
          group.addClass("viewHidden")
          val _a = group.hide()
        }
      }
    }
    cy.batch(fn)
  }

  private def hideNodes(cy: CyInstance, ids: Set[String]): Unit = {
    val fn: js.Function0[Unit] = () => {
      for (id <- ids) {
        if (nodeExists(cy, id)) {
          val node = cy.getElementById(id)
          node.addClass("viewHidden")
          node.removeClass("selected")
          val _a = node.hide()
          node.connectedEdges().forEach { (edge: CyElement) =>
            val src = edge.data("source").asInstanceOf[String]
            val tgt = edge.data("target").asInstanceOf[String]
            val otherId = if (src == id) tgt else src
            val other = cy.getElementById(otherId)
            if (other.hasClass("viewHidden")) {
              val _b = edge.hide()
            }
          }
        }
      }
    }
    cy.batch(fn)
  }

  private def showNode(cy: CyInstance, id: String): Unit = {
    if (nodeExists(cy, id)) {
      val node = cy.getElementById(id)
      node.removeClass("viewHidden")
      val _a = node.show()
      // Show the parent group too
      val parentId = node.data("parent")
      if (!js.isUndefined(parentId) && parentId != null) {
        val parent = cy.getElementById(parentId.asInstanceOf[String])
        if (parent.hasClass("viewHidden")) {
          parent.removeClass("viewHidden")
          val _b = parent.show()
        }
      }
      // Show connected edges to visible nodes
      node.connectedEdges().forEach { (edge: CyElement) =>
        val src = edge.data("source").asInstanceOf[String]
        val tgt = edge.data("target").asInstanceOf[String]
        val otherId = if (src == id) tgt else src
        val other = cy.getElementById(otherId)
        if (!other.hasClass("viewHidden")) {
          val _b = edge.show()
        }
      }
    }
  }

  private def getCurrentView(cy: CyInstance, name: String): View = {
    val hidden = scala.collection.mutable.Set[String]()
    cy.nodes().forEach { (node: CyElement) =>
      if (node.hasClass("viewHidden")) hidden += node.id()
    }
    View(name, hidden.toSet)
  }

  private def updateSavedView(savedViews: Var[List[View]], view: View): Unit = {
    val views = savedViews.now().map(v => if (v.name == view.name) view else v)
    savedViews.set(views)
    // Don't persist the auto-generated "Connected only" view
    ViewStore.saveAll(views.filterNot(v => v.name == "Connected only" || v.name == "All"))
  }

  private def clearSelection(cy: CyInstance): Unit = {
    val _ = cy.nodes().removeClass("selected")
  }

  private def showHiddenPanel(
      cy: CyInstance,
      savedViews: Var[List[View]],
      activeViewName: Var[String],
      activeLayout: Var[String],
  ): Unit = {
    val panel = dom.document.getElementById("details")
    if (panel == null) return
    panel.innerHTML = ""
    panel.classList.add("open")

    val header = dom.document.createElement("div")
    header.classList.add("details-header")
    val titleEl = dom.document.createElement("span")
    titleEl.textContent = "Hidden Elements"
    header.appendChild(titleEl)
    val closeBtn = dom.document.createElement("button")
    closeBtn.textContent = "✕"
    closeBtn.addEventListener("click", (_: dom.Event) => closeDetails())
    header.appendChild(closeBtn)
    panel.appendChild(header)

    // Collect hidden nodes by package/group
    val hiddenNodes = scala.collection.mutable.ListBuffer[(String, String, String)]() // (id, label, package/group)
    cy.nodes().forEach { (node: CyElement) =>
      if (node.hasClass("viewHidden")) {
        val nt = node.data("nodeType")
        if (!js.isUndefined(nt) && nt != null) {
          val nodeType = nt.asInstanceOf[String]
          if (nodeType == "class" || nodeType == "resource") {
            val label = node.data("label").asInstanceOf[String]
            val pkg = node.data("packageName")
            val pkgStr = if (js.isUndefined(pkg) || pkg == null) "" else pkg.asInstanceOf[String]
            hiddenNodes += ((node.id(), label, pkgStr))
          }
        }
      }
    }

    if (hiddenNodes.isEmpty) {
      val msg = dom.document.createElement("div")
      msg.setAttribute("style", "padding:14px;color:#888;")
      msg.textContent = "No hidden elements."
      panel.appendChild(msg)
      return
    }

    // Show all button
    val showAllDiv = dom.document.createElement("div")
    showAllDiv.setAttribute("style", "padding:10px 14px;border-bottom:1px solid #eee;")
    val showAllBtn = dom.document.createElement("button")
    showAllBtn.setAttribute("style", "cursor:pointer;padding:4px 8px;border-radius:4px;border:1px solid #ccc;background:#f8f8f8;font-size:11px;")
    showAllBtn.textContent = s"Show all (${hiddenNodes.size})"
    showAllBtn.addEventListener("click", (_: dom.Event) => {
      hiddenNodes.foreach { case (id, _, _) => showNode(cy, id) }
      val v = getCurrentView(cy, activeViewName.now())
      updateSavedView(savedViews, v)
      runLayout(cy, activeLayout.now())
      showHiddenPanel(cy, savedViews, activeViewName, activeLayout) // refresh
    })
    showAllDiv.appendChild(showAllBtn)
    panel.appendChild(showAllDiv)

    // Group by package
    val byPackage = hiddenNodes.toList.groupBy(_._3).toList.sortBy(_._1)
    for ((pkg, nodes) <- byPackage) {
      val section = dom.document.createElement("div")
      section.classList.add("details-section")
      val h4 = dom.document.createElement("h4")
      h4.textContent = if (pkg.isEmpty) "No package" else pkg.split('.').takeRight(2).mkString(".")
      section.appendChild(h4)

      // Show all in package button
      val showPkgBtn = dom.document.createElement("button")
      showPkgBtn.setAttribute("style", "cursor:pointer;padding:2px 6px;border-radius:3px;border:1px solid #ccc;background:#f8f8f8;font-size:10px;margin-bottom:6px;")
      showPkgBtn.textContent = s"Show all (${nodes.size})"
      showPkgBtn.addEventListener("click", (_: dom.Event) => {
        nodes.foreach { case (id, _, _) => showNode(cy, id) }
        val v = getCurrentView(cy, activeViewName.now())
        updateSavedView(savedViews, v)
        runLayout(cy, activeLayout.now())
        showHiddenPanel(cy, savedViews, activeViewName, activeLayout)
      })
      section.appendChild(showPkgBtn)

      for ((id, label, _) <- nodes.sortBy(_._2)) {
        val item = dom.document.createElement("div")
        item.setAttribute("style", "padding:3px 0;display:flex;justify-content:space-between;align-items:center;")
        val nameEl = dom.document.createElement("span")
        nameEl.textContent = label
        nameEl.setAttribute("style", "font-size:11px;")
        item.appendChild(nameEl)
        val btn = dom.document.createElement("button")
        btn.setAttribute("style", "cursor:pointer;padding:1px 6px;border-radius:3px;border:1px solid #ccc;background:#f8f8f8;font-size:10px;")
        btn.textContent = "Show"
        btn.addEventListener("click", (_: dom.Event) => {
          showNode(cy, id)
          val v = getCurrentView(cy, activeViewName.now())
          updateSavedView(savedViews, v)
          runLayout(cy, activeLayout.now())
          showHiddenPanel(cy, savedViews, activeViewName, activeLayout)
        })
        item.appendChild(btn)
        section.appendChild(item)
      }
      panel.appendChild(section)
    }
  }

  // ── Add class to graph dynamically ──

  /** Add a class node + its edges to the live Cytoscape graph. */
  private def addClassToGraph(cy: CyInstance, classId: String, data: LineageData, activeLayout: Var[String]): Unit = {
    if (nodeExists(cy, classId)) return
    data.classes.find(_.classId == classId) match {
      case None => ()
      case Some(cls) =>
        val bg     = GraphBuilder.accessColors.getOrElse(cls.effectiveAccess, "#ffffff")
        val border = GraphBuilder.accessBorders.getOrElse(cls.effectiveAccess, "#cccccc")
        val parentId = cls.group.map { g =>
          val gid = s"group_$g"
          // Create group if it doesn't exist
          if (!nodeExists(cy, gid)) {
            val _g = cy.add(js.Dynamic.literal(
              group = "nodes",
              data = js.Dynamic.literal(id = gid, label = g, nodeType = "classGroup"),
              classes = "compound classGroup",
            ).asInstanceOf[js.Object])
          }
          gid
        }.orNull

        // Add class node
        val _n = cy.add(js.Dynamic.literal(
          group = "nodes",
          data = js.Dynamic.literal(
            id = classId,
            label = cls.displayName,
            parent = parentId,
            nodeType = "class",
            packageName = cls.packageName,
            bg = bg,
            borderColor = border,
            accessType = cls.effectiveAccess,
          ),
          classes = s"classNode ${cls.effectiveAccess}",
        ).asInstanceOf[js.Object])

        // Add call edges to/from existing nodes
        val existingClassIds = scala.collection.mutable.Set[String]()
        cy.nodes(".classNode").forEach { (n: CyElement) => existingClassIds += n.id() }

        val callEdges = data.callGraph
          .map(e => (e.caller.classId, e.callee.classId))
          .distinct
          .filter { case (from, to) =>
            from != to && (
              (from == classId && existingClassIds.contains(to)) ||
              (to == classId && existingClassIds.contains(from))
            )
          }
        for ((from, to) <- callEdges) {
          val edgeId = s"call_${from}_$to"
          if (!nodeExists(cy, edgeId)) { // reuse nodeExists for edge check
            val _e = cy.add(js.Dynamic.literal(
              group = "edges",
              data = js.Dynamic.literal(id = edgeId, source = from, target = to, edgeType = "call"),
              classes = "callEdge",
            ).asInstanceOf[js.Object])
          }
        }

        // Add integration edges to existing resources
        val intEdges = data.integrations
          .filter(i => i.method.packageName == cls.packageName && i.method.className == cls.name)
          .map(i => (classId, s"res_${i.target}", i.accessType))
          .filter { case (_, resId, _) => nodeExists(cy, resId) }
          .groupBy { case (from, to, _) => (from, to) }
          .map { case ((from, to), entries) =>
            (from, to, GraphBuilder.combineAccess(entries.map(_._3).toList))
          }
        for ((from, to, access) <- intEdges) {
          val label = access match {
            case a => GraphBuilder.accessLabel(a)
          }
          val _e = cy.add(js.Dynamic.literal(
            group = "edges",
            data = js.Dynamic.literal(
              id = s"int_${from}_$to", source = from, target = to,
              label = label, edgeType = "integration", accessType = access,
            ),
            classes = s"integrationEdge $access",
          ).asInstanceOf[js.Object])
        }

        runLayout(cy, activeLayout.now())
    }
  }

  /** Remove a dynamically added class from the graph. */
  private def removeClassFromGraph(cy: CyInstance, classId: String, activeLayout: Var[String]): Unit = {
    if (!nodeExists(cy, classId)) return
    val node = cy.getElementById(classId)
    val _e = node.connectedEdges().remove()
    val _n = node.asInstanceOf[CyCollection].remove()
    runLayout(cy, activeLayout.now())
  }

  // ── Add classes panel ──

  private def showAddClassesPanel(cy: CyInstance, data: LineageData, activeLayout: Var[String]): Unit = {
    val panel = dom.document.getElementById("details")
    if (panel == null) return
    panel.innerHTML = ""
    panel.classList.add("open")

    detailsHeader(panel, "Add Classes")

    // Search input
    val searchDiv = dom.document.createElement("div")
    searchDiv.setAttribute("style", "padding:10px 14px;border-bottom:1px solid #eee;")
    val searchInput = dom.document.createElement("input").asInstanceOf[dom.html.Input]
    searchInput.setAttribute("type", "text")
    searchInput.setAttribute("placeholder", "Search by name or package...")
    searchInput.setAttribute("style", "width:100%;padding:4px 8px;border:1px solid #ccc;border-radius:4px;font-size:11px;box-sizing:border-box;")
    searchDiv.appendChild(searchInput)
    panel.appendChild(searchDiv)

    val listContainer = dom.document.createElement("div")
    listContainer.setAttribute("style", "overflow-y:auto;max-height:calc(100vh - 120px);")
    panel.appendChild(listContainer)

    def renderList(query: String): Unit = {
      listContainer.innerHTML = ""
      val lowerQuery = query.toLowerCase.trim

      // Find classes not currently in the graph
      val existingIds = scala.collection.mutable.Set[String]()
      cy.nodes(".classNode").forEach { (n: CyElement) => existingIds += n.id() }

      val hiddenClasses = data.classes
        .filterNot(c => existingIds.contains(c.classId))
        .filter { c =>
          if (lowerQuery.isEmpty) true
          else c.name.toLowerCase.contains(lowerQuery) || c.packageName.toLowerCase.contains(lowerQuery)
        }
        .sortBy(c => (c.packageName, c.name))

      if (hiddenClasses.isEmpty) {
        val msg = dom.document.createElement("div")
        msg.setAttribute("style", "padding:14px;color:#888;")
        msg.textContent = if (lowerQuery.isEmpty) "All classes are on the diagram." else "No matching classes found."
        listContainer.appendChild(msg)
        return
      }

      val countMsg = dom.document.createElement("div")
      countMsg.setAttribute("style", "padding:6px 14px;color:#888;font-size:10px;border-bottom:1px solid #f0f0f0;")
      countMsg.textContent = s"${hiddenClasses.size} classes available"
      listContainer.appendChild(countMsg)

      // Group by package, show max 100 to avoid lag
      val shown = if (lowerQuery.nonEmpty) hiddenClasses.take(100) else hiddenClasses.take(50)
      val byPackage = shown.groupBy(_.packageName).toList.sortBy(_._1)
      for ((pkg, classes) <- byPackage) {
        val section = dom.document.createElement("div")
        section.setAttribute("style", "border-bottom:1px solid #f0f0f0;")
        val h4 = dom.document.createElement("div")
        h4.setAttribute("style", "padding:6px 14px 2px;font-size:10px;color:#888;font-weight:600;text-transform:uppercase;letter-spacing:0.5px;")
        val shortPkg = pkg.split('.').takeRight(3).mkString(".")
        h4.textContent = shortPkg
        section.appendChild(h4)

        for (cls <- classes.sortBy(_.name)) {
          val item = dom.document.createElement("div")
          item.setAttribute("style", "padding:3px 14px;display:flex;justify-content:space-between;align-items:center;cursor:pointer;")
          item.addEventListener("mouseenter", (_: dom.Event) => item.setAttribute("style", "padding:3px 14px;display:flex;justify-content:space-between;align-items:center;cursor:pointer;background:#f8f8f8;"))
          item.addEventListener("mouseleave", (_: dom.Event) => item.setAttribute("style", "padding:3px 14px;display:flex;justify-content:space-between;align-items:center;cursor:pointer;"))

          val nameEl = dom.document.createElement("span")
          nameEl.setAttribute("style", "font-size:11px;")
          nameEl.textContent = cls.name
          item.appendChild(nameEl)

          val btnRow = dom.document.createElement("span")

          // Info button — shows details of this hidden class
          val infoBtn = dom.document.createElement("button")
          infoBtn.setAttribute("style", "cursor:pointer;padding:1px 6px;border-radius:3px;border:1px solid #ccc;background:#f8f8f8;font-size:10px;margin-right:4px;")
          infoBtn.textContent = "Info"
          infoBtn.addEventListener("click", { (e: dom.Event) =>
            e.stopPropagation()
            renderHiddenClassDetails(panel, cy, cls, data, activeLayout)
          })
          btnRow.appendChild(infoBtn)

          val addBtn = dom.document.createElement("button")
          addBtn.setAttribute("style", "cursor:pointer;padding:1px 6px;border-radius:3px;border:1px solid #4a90d9;background:#4a90d9;color:white;font-size:10px;")
          addBtn.textContent = "Add"
          addBtn.addEventListener("click", { (e: dom.Event) =>
            e.stopPropagation()
            addClassToGraph(cy, cls.classId, data, activeLayout)
            renderList(searchInput.value) // refresh list
          })
          btnRow.appendChild(addBtn)
          item.appendChild(btnRow)
          section.appendChild(item)
        }
        listContainer.appendChild(section)
      }

      if (hiddenClasses.size > shown.size) {
        val moreMsg = dom.document.createElement("div")
        moreMsg.setAttribute("style", "padding:10px 14px;color:#888;font-size:10px;font-style:italic;")
        moreMsg.textContent = s"${hiddenClasses.size - shown.size} more... Refine your search to see them."
        listContainer.appendChild(moreMsg)
      }
    }

    renderList("")
    searchInput.addEventListener("input", (_: dom.Event) => renderList(searchInput.value))
    searchInput.focus()
  }

  /** Render details panel for a class that is NOT on the graph. */
  private def renderHiddenClassDetails(panel: dom.Element, cy: CyInstance, cls: ClassInfo, data: LineageData, activeLayout: Var[String]): Unit = {
    panel.innerHTML = ""
    panel.classList.add("open")
    detailsHeader(panel, cls.displayName)

    val infoSection = detailsSection(panel, "Info")
    detailsRow(infoSection, "Package", cls.packageName)
    cls.group.foreach(g => detailsRow(infoSection, "Group", g))
    val accessRow = dom.document.createElement("div")
    accessRow.classList.add("details-row")
    val lbl = dom.document.createElement("span")
    lbl.classList.add("label")
    lbl.textContent = "Access"
    accessRow.appendChild(lbl)
    accessRow.appendChild(accessTag(cls.effectiveAccess))
    infoSection.appendChild(accessRow)
    detailsRow(infoSection, "On diagram", "No")

    // Add to diagram button
    val addDiv = dom.document.createElement("div")
    addDiv.setAttribute("style", "padding:10px 14px;border-bottom:1px solid #eee;")
    val addBtn = dom.document.createElement("button")
    addBtn.setAttribute("style", "cursor:pointer;padding:4px 12px;border-radius:4px;border:1px solid #4a90d9;background:#4a90d9;color:white;font-size:11px;")
    addBtn.textContent = "Add to diagram"
    addBtn.addEventListener("click", (_: dom.Event) => {
      addClassToGraph(cy, cls.classId, data, activeLayout)
      // Refresh to show as on-diagram
      data.classes.find(_.classId == cls.classId).foreach { c =>
        if (nodeExists(cy, cls.classId)) {
          val node = cy.getElementById(cls.classId)
          showDetails(cy, node, data, activeLayout)
        }
      }
    })
    addDiv.appendChild(addBtn)
    panel.appendChild(addDiv)

    // Methods
    val nonPureMethods = cls.methods.filter(_.effectiveAccess != "Pure")
    if (nonPureMethods.nonEmpty) {
      val methodSection = detailsSection(panel, s"Methods (${cls.methods.size} total, ${nonPureMethods.size} with I/O)")
      val list = dom.document.createElement("ul")
      list.classList.add("method-list")
      for (m <- nonPureMethods) {
        val li = dom.document.createElement("li")
        val name = dom.document.createElement("span")
        name.classList.add("method-name")
        name.textContent = m.ref.methodName
        li.appendChild(name)
        li.appendChild(accessTag(m.effectiveAccess))
        list.appendChild(li)
      }
      methodSection.appendChild(list)
    }

    // Connections from data (not graph)
    renderDataConnections(panel, cy, cls, data, activeLayout)
  }

  /** Show connections from LineageData for a class — includes non-rendered classes. */
  private def renderDataConnections(panel: dom.Element, cy: CyInstance, cls: ClassInfo, data: LineageData, activeLayout: Var[String]): Unit = {
    // Call graph connections
    val callsTo = data.callGraph
      .filter(e => e.caller.packageName == cls.packageName && e.caller.className == cls.name)
      .map(e => (e.callee.packageName, e.callee.className))
      .distinct
    val callsFrom = data.callGraph
      .filter(e => e.callee.packageName == cls.packageName && e.callee.className == cls.name)
      .map(e => (e.caller.packageName, e.caller.className))
      .distinct

    if (callsTo.nonEmpty) {
      val section = detailsSection(panel, s"Calls to (${callsTo.size})")
      for ((pkg, name) <- callsTo.sortBy(_._2)) {
        renderConnectionItem(section, cy, pkg, name, data, activeLayout)
      }
    }
    if (callsFrom.nonEmpty) {
      val section = detailsSection(panel, s"Called by (${callsFrom.size})")
      for ((pkg, name) <- callsFrom.sortBy(_._2)) {
        renderConnectionItem(section, cy, pkg, name, data, activeLayout)
      }
    }

    // Integration connections
    val integrations = data.integrations.filter(i =>
      i.method.packageName == cls.packageName && i.method.className == cls.name,
    )
    if (integrations.nonEmpty) {
      val section = detailsSection(panel, s"Resources (${integrations.size})")
      for (i <- integrations.sortBy(_.target)) {
        val item = dom.document.createElement("div")
        item.classList.add("connection-item")
        val nameEl = dom.document.createElement("span")
        nameEl.classList.add("connection-name")
        nameEl.textContent = s"[${i.resourceType}] ${i.target.split("/").last}"
        item.appendChild(nameEl)
        item.appendChild(accessTag(i.accessType))
        section.appendChild(item)
      }
    }
  }

  /** Render a connection item for a class — shows whether it's on the diagram and allows adding/navigating. */
  private def renderConnectionItem(parent: dom.Element, cy: CyInstance, pkg: String, name: String, data: LineageData, activeLayout: Var[String]): Unit = {
    val classId = s"cls_${pkg.hashCode.abs}_$name"
    val onDiagram = nodeExists(cy, classId)
    val item = dom.document.createElement("div")
    item.setAttribute("style", "padding:3px 0;display:flex;justify-content:space-between;align-items:center;")

    val nameEl = dom.document.createElement("span")
    nameEl.classList.add("connection-name")
    nameEl.setAttribute("style", if (onDiagram) "cursor:pointer;color:#333;" else "color:#999;")
    nameEl.textContent = name
    if (onDiagram) {
      nameEl.addEventListener("click", (_: dom.Event) => {
        val node = cy.getElementById(classId)
        showDetails(cy, node, data, activeLayout)
        cy.asInstanceOf[js.Dynamic].center(node)
      })
    }
    item.appendChild(nameEl)

    if (!onDiagram) {
      val btnRow = dom.document.createElement("span")
      val infoBtn = dom.document.createElement("button")
      infoBtn.setAttribute("style", "cursor:pointer;padding:1px 6px;border-radius:3px;border:1px solid #ccc;background:#f8f8f8;font-size:10px;margin-right:4px;")
      infoBtn.textContent = "Info"
      infoBtn.addEventListener("click", (_: dom.Event) => {
        data.classes.find(_.classId == classId).foreach { c =>
          val panel = dom.document.getElementById("details")
          if (panel != null) renderHiddenClassDetails(panel, cy, c, data, activeLayout)
        }
      })
      btnRow.appendChild(infoBtn)

      val addBtn = dom.document.createElement("button")
      addBtn.setAttribute("style", "cursor:pointer;padding:1px 6px;border-radius:3px;border:1px solid #4a90d9;background:#4a90d9;color:white;font-size:10px;")
      addBtn.textContent = "Add"
      addBtn.addEventListener("click", (_: dom.Event) => {
        addClassToGraph(cy, classId, data, activeLayout)
      })
      btnRow.appendChild(addBtn)
      item.appendChild(btnRow)
    } else {
      val badge = dom.document.createElement("span")
      badge.setAttribute("style", "font-size:9px;color:#28a745;")
      badge.textContent = "on diagram"
      item.appendChild(badge)
    }
    parent.appendChild(item)
  }

  // ── Details pane ──

  private def showDetails(
      cy: CyInstance,
      node: CyElement,
      data: LineageData,
      activeLayout: Var[String],
      multi: Option[MultiServiceData] = None,
      graphData: Option[GraphBuilder.GraphData] = None,
  ): Unit = {
    val panel = dom.document.getElementById("details")
    if (panel == null) return
    panel.innerHTML = ""
    panel.classList.add("open")

    val nodeType = {
      val nt = node.data("nodeType")
      if (js.isUndefined(nt) || nt == null) "" else nt.asInstanceOf[String]
    }

    nodeType match {
      case "class"         => renderClassDetails(panel, cy, node, data, activeLayout)
      case "resource"      =>
        renderResourceDetails(panel, cy, node, data)
        multi.foreach(m => renderCrossServiceAccess(panel, node, m, data))
      case "classGroup"    => renderGroupDetails(panel, cy, node, data, "Class Group")
      case "resourceGroup" =>
        renderGroupDetails(panel, cy, node, data, "Resource Group")
        multi.foreach(m => renderCrossServiceAccess(panel, node, m, data))
        graphData.foreach(gd => renderGroupExpandCollapse(panel, cy, node, data, activeLayout, multi, gd))
      case "service"       => multi.foreach(m => renderServiceDetails(panel, node, m))
      case _               => panel.classList.remove("open")
    }
  }

  /** Render cross-service access section for resource/resourceGroup nodes in system view. */
  private def renderCrossServiceAccess(panel: dom.Element, node: CyElement, multi: MultiServiceData, mergedData: LineageData): Unit = {
    val config = mergedData.resourceDisplayConfig
    val nodeType = node.data("nodeType").asInstanceOf[String]

    // Collect resource keys that belong to this node (single resource or all children of a group)
    val resourceKeys = if (nodeType == "resourceGroup") {
      val keys = scala.collection.mutable.Set[String]()
      def collectKeys(n: CyElement): Unit = {
        n.children().forEach { (child: CyElement) =>
          val rk = child.data("resourceKey")
          if (!js.isUndefined(rk) && rk != null) keys += rk.asInstanceOf[String]
          collectKeys(child)
        }
      }
      collectKeys(node)
      keys.toSet
    } else {
      val rk = node.data("resourceKey")
      if (!js.isUndefined(rk) && rk != null) Set(rk.asInstanceOf[String]) else Set.empty[String]
    }

    if (resourceKeys.isEmpty) return

    val serviceAccess = multi.services.flatMap { svc =>
      val accesses = svc.data.integrations
        .filter { i =>
          val fk = GraphBuilder.foldedNodeKey(i.segments, i.resourceType, config)
          resourceKeys.contains(fk)
        }
        .map(_.accessType)
      if (accesses.nonEmpty) Some((svc.name, GraphBuilder.combineAccess(accesses))) else None
    }

    if (serviceAccess.nonEmpty) {
      val section = detailsSection(panel, s"Services (${serviceAccess.size})")
      for ((name, access) <- serviceAccess.sortBy(_._1)) {
        val item = dom.document.createElement("div")
        item.classList.add("connection-item")
        val nameEl = dom.document.createElement("span")
        nameEl.classList.add("connection-name")
        nameEl.textContent = name
        item.appendChild(nameEl)
        item.appendChild(accessTag(access))
        section.appendChild(item)
      }
    }
  }

  /** Render expand/collapse buttons for resource group nodes in system view. */
  private def renderGroupExpandCollapse(
      panel: dom.Element,
      cy: CyInstance,
      node: CyElement,
      data: LineageData,
      activeLayout: Var[String],
      multi: Option[MultiServiceData],
      graphData: GraphBuilder.GraphData,
  ): Unit = {
    val nodeId = node.id()
    if (!graphData.expandableElements.contains(nodeId)) return
    val hasShared = graphData.sharedResourceIds.getOrElse(nodeId, Set.empty).nonEmpty
    val isCollapsed = node.hasClass("collapsedGroup")
    val isSharedOnly = node.hasClass("sharedOnly")
    val actionsSection = detailsSection(panel, "Actions")
    val btnStyle = "padding:4px 12px;cursor:pointer;font-size:11px;margin-right:4px;"

    def reopen(): Unit = showDetails(cy, node, data, activeLayout, multi, Some(graphData))

    def actionButton(text: String)(action: => Unit): Unit = {
      val btn = dom.document.createElement("button")
      btn.textContent = text
      btn.setAttribute("style", btnStyle)
      btn.addEventListener("click", { (_: dom.Event) => action; runLayout(cy, activeLayout.now()); reopen() })
      actionsSection.appendChild(btn)
    }

    if (isCollapsed) {
      if (hasShared) actionButton("Expand shared") { expandResourceGroup(cy, nodeId, graphData, sharedOnly = true) }
      actionButton("Expand all") { expandResourceGroup(cy, nodeId, graphData, sharedOnly = false) }
    } else {
      if (isSharedOnly && hasShared) actionButton("Show all resources") {
        collapseResourceGroup(cy, nodeId, graphData)
        expandResourceGroup(cy, nodeId, graphData, sharedOnly = false)
      }
      actionButton("Collapse") { collapseResourceGroup(cy, nodeId, graphData) }
    }
  }

  /** Render details for a service node. */
  private def renderServiceDetails(panel: dom.Element, node: CyElement, multi: MultiServiceData): Unit = {
    val serviceName = node.data("label").asInstanceOf[String]
    detailsHeader(panel, serviceName)

    multi.services.find(_.name == serviceName).foreach { svc =>
      val infoSection = detailsSection(panel, "Info")
      detailsRow(infoSection, "Type", "Service")
      detailsRow(infoSection, "Classes", svc.data.classes.size.toString)
      detailsRow(infoSection, "Integrations", svc.data.integrations.size.toString)
      detailsRow(infoSection, "Resources", svc.data.resources.size.toString)

      val config = multi.services.flatMap(_.data.resourceDisplayConfig.toList).toMap
      val segLabels = multi.services.flatMap(_.data.segmentLabels.toList).toMap
      val groups = svc.data.resources.flatMap { r =>
        val effSegs = GraphBuilder.effectiveSegments(r.segments, r.resourceType, config)
        effSegs.headOption.map(s => (r.resourceType, segLabels.getOrElse(s.value, s.value)))
      }.distinct.sortBy(_._2)

      if (groups.nonEmpty) {
        val section = detailsSection(panel, s"Resource Groups (${groups.size})")
        for ((rtype, label) <- groups) {
          val item = dom.document.createElement("div")
          item.setAttribute("style", "padding:3px 0;font-size:11px;")
          item.textContent = s"[$rtype] $label"
          section.appendChild(item)
        }
      }
    }
  }

  private def closeDetails(): Unit = {
    val panel = dom.document.getElementById("details")
    if (panel != null) {
      panel.classList.remove("open")
      panel.innerHTML = ""
    }
  }

  private def detailsHeader(panel: dom.Element, title: String): Unit = {
    val header = dom.document.createElement("div")
    header.classList.add("details-header")
    val titleEl = dom.document.createElement("span")
    titleEl.textContent = title
    header.appendChild(titleEl)
    val closeBtn = dom.document.createElement("button")
    closeBtn.textContent = "✕"
    closeBtn.addEventListener("click", (_: dom.Event) => closeDetails())
    header.appendChild(closeBtn)
    panel.appendChild(header)
  }

  private def detailsSection(panel: dom.Element, title: String): dom.Element = {
    val section = dom.document.createElement("div")
    section.classList.add("details-section")
    val h4 = dom.document.createElement("h4")
    h4.textContent = title
    section.appendChild(h4)
    panel.appendChild(section)
    section
  }

  private def detailsRow(parent: dom.Element, label: String, value: String): Unit = {
    val row = dom.document.createElement("div")
    row.classList.add("details-row")
    val lbl = dom.document.createElement("span")
    lbl.classList.add("label")
    lbl.textContent = label
    row.appendChild(lbl)
    val v = dom.document.createElement("span")
    v.classList.add("value")
    v.textContent = value
    row.appendChild(v)
    parent.appendChild(row)
  }

  private def accessTag(access: String): dom.Element = {
    val tag = dom.document.createElement("span")
    tag.classList.add("tag")
    access match {
      case "Read"      => tag.classList.add("tag-read"); tag.textContent = "Read"
      case "Write"     => tag.classList.add("tag-write"); tag.textContent = "Write"
      case "ReadWrite" => tag.classList.add("tag-readwrite"); tag.textContent = "ReadWrite"
      case _           => tag.classList.add("tag-pure"); tag.textContent = access
    }
    tag
  }

  private def renderClassDetails(panel: dom.Element, cy: CyInstance, node: CyElement, data: LineageData, activeLayout: Var[String]): Unit = {
    val nodeId = node.id()
    val label  = node.data("label").asInstanceOf[String]
    val access = node.data("accessType").asInstanceOf[String]
    detailsHeader(panel, label)

    // Find class info from data
    val classInfo = data.classes.find(_.classId == nodeId)

    // Basic info
    val infoSection = detailsSection(panel, "Info")
    classInfo.foreach { cls =>
      detailsRow(infoSection, "Package", cls.packageName)
      cls.group.foreach(g => detailsRow(infoSection, "Group", g))
    }
    val accessRow = dom.document.createElement("div")
    accessRow.classList.add("details-row")
    val lbl = dom.document.createElement("span")
    lbl.classList.add("label")
    lbl.textContent = "Access"
    accessRow.appendChild(lbl)
    accessRow.appendChild(accessTag(access))
    infoSection.appendChild(accessRow)

    // Methods
    classInfo.foreach { cls =>
      val nonPureMethods = cls.methods.filter(_.effectiveAccess != "Pure")
      if (nonPureMethods.nonEmpty) {
        val methodSection = detailsSection(panel, s"Methods (${cls.methods.size} total, ${nonPureMethods.size} with I/O)")
        val list = dom.document.createElement("ul")
        list.classList.add("method-list")
        for (m <- nonPureMethods) {
          val li = dom.document.createElement("li")
          val name = dom.document.createElement("span")
          name.classList.add("method-name")
          name.textContent = m.ref.methodName
          li.appendChild(name)
          li.appendChild(accessTag(m.effectiveAccess))
          list.appendChild(li)
        }
        methodSection.appendChild(list)
      }
    }

    // Connections from data (includes non-rendered classes)
    classInfo.foreach { cls =>
      renderDataConnections(panel, cy, cls, data, activeLayout)
    }
  }


  private def renderResourceDetails(panel: dom.Element, cy: CyInstance, node: CyElement, data: LineageData): Unit = {
    val nodeId = node.id()
    val label  = node.data("label").asInstanceOf[String]
    val rtype  = {
      val rt = node.data("resourceType")
      if (js.isUndefined(rt) || rt == null) "" else rt.asInstanceOf[String]
    }
    detailsHeader(panel, label)

    // Basic info
    val infoSection = detailsSection(panel, "Info")
    if (rtype.nonEmpty) detailsRow(infoSection, "Type", rtype)

    // Show resource key
    val resourceKeyData = node.data("resourceKey")
    val resourceKey = if (!js.isUndefined(resourceKeyData) && resourceKeyData != null) resourceKeyData.asInstanceOf[String] else ""
    if (resourceKey.nonEmpty) detailsRow(infoSection, "Key", resourceKey)

    // Find all resources that fold into this node
    val config = data.resourceDisplayConfig
    val segLabels = data.segmentLabels
    val matchingResources = data.resources.filter { r =>
      GraphBuilder.foldedNodeKey(r.segments, r.resourceType, config) == resourceKey
    }

    // Show segments from the first match (shared segments up to fold level)
    for (res <- matchingResources.headOption) {
      val segSection = detailsSection(panel, "Segments")
      for (seg <- res.segments) {
        val display = segLabels.getOrElse(seg.value, seg.value)
        val segLabel = if (display != seg.value) s"$display (${seg.value})" else seg.value
        detailsRow(segSection, seg.level, segLabel)
      }
    }

    // If this is a folded node with multiple resources, show their targets
    if (matchingResources.size > 1) {
      val foldedSection = detailsSection(panel, s"Contains (${matchingResources.size})")
      for (res <- matchingResources.sortBy(_.target)) {
        val item = dom.document.createElement("div")
        item.classList.add("connection-item")
        item.textContent = res.target
        foldedSection.appendChild(item)
      }
    }

    // Show parent group
    val parentData = node.data("parent")
    if (!js.isUndefined(parentData) && parentData != null) {
      val parentNode = cy.getElementById(parentData.asInstanceOf[String])
      val pl = parentNode.data("label")
      if (!js.isUndefined(pl) && pl != null) detailsRow(infoSection, "Group", pl.asInstanceOf[String])
    }

    // Connected classes
    val connections = scala.collection.mutable.ListBuffer[(String, String)]() // (label, access)
    node.connectedEdges().forEach { (edge: CyElement) =>
      val et = edge.data("edgeType")
      if (!js.isUndefined(et) && et != null && et.asInstanceOf[String] == "integration") {
        val src = edge.data("source").asInstanceOf[String]
        val tgt = edge.data("target").asInstanceOf[String]
        val otherId = if (src == nodeId) tgt else src
        val other = cy.getElementById(otherId)
        val otherLabel = other.data("label")
        val at = edge.data("accessType")
        if (!js.isUndefined(otherLabel) && otherLabel != null) {
          connections += ((
            otherLabel.asInstanceOf[String],
            if (js.isUndefined(at) || at == null) "" else at.asInstanceOf[String],
          ))
        }
      }
    }
    if (connections.nonEmpty) {
      val connSection = detailsSection(panel, s"Accessed by (${connections.size})")
      for ((name, acc) <- connections.sortBy(_._1)) {
        val item = dom.document.createElement("div")
        item.classList.add("connection-item")
        val nameEl = dom.document.createElement("span")
        nameEl.classList.add("connection-name")
        nameEl.textContent = name
        item.appendChild(nameEl)
        item.appendChild(accessTag(acc))
        connSection.appendChild(item)
      }
    }

    // Discovery evidence — combine integration evidence with resource discoveries
    val integrationEvidence = data.integrations.filter { i =>
      GraphBuilder.foldedNodeKey(i.segments, i.resourceType, config) == resourceKey
    }.map(e => (e.scanner, e.evidence))
    val resourceEvidence = matchingResources.flatMap(_.discoveries).map(d => (d.scanner, d.evidence))
    val allEvidence = (integrationEvidence ++ resourceEvidence).distinct
    if (allEvidence.nonEmpty) {
      val evSection = detailsSection(panel, "Discovery evidence")
      for ((scannerName, evidenceText) <- allEvidence) {
        val item = dom.document.createElement("div")
        item.setAttribute("style", "padding:3px 0;font-size:11px;")
        val scanner = dom.document.createElement("span")
        scanner.setAttribute("style", "color:#888;")
        scanner.textContent = s"[$scannerName] "
        item.appendChild(scanner)
        val ev = dom.document.createElement("span")
        ev.setAttribute("style", "font-family:monospace;word-break:break-all;")
        ev.textContent = evidenceText
        item.appendChild(ev)
        evSection.appendChild(item)
      }
    }
  }

  private def renderGroupDetails(panel: dom.Element, cy: CyInstance, node: CyElement, data: LineageData, kind: String): Unit = {
    val label = node.data("label").asInstanceOf[String]
    detailsHeader(panel, label)

    val infoSection = detailsSection(panel, "Info")
    detailsRow(infoSection, "Type", kind)

    // Show resource type if available
    val rtype = node.data("resourceType")
    if (!js.isUndefined(rtype) && rtype != null) detailsRow(infoSection, "Resource type", rtype.asInstanceOf[String])

    // Show segments for resource groups
    val segmentsData = node.data("segments")
    if (!js.isUndefined(segmentsData) && segmentsData != null) {
      val segLabels = data.segmentLabels
      val segs = segmentsData.asInstanceOf[js.Array[js.Dynamic]]
      if (segs.length > 0) {
        val segSection = detailsSection(panel, "Segments")
        for (i <- 0 until segs.length) {
          val seg = segs(i)
          val level = seg.level.asInstanceOf[String]
          val value = seg.value.asInstanceOf[String]
          val display = segLabels.getOrElse(value, value)
          val segLabel = if (display != value) s"$display ($value)" else value
          detailsRow(segSection, level, segLabel)
        }
      }
    }

    val children = node.children()
    val childNames = scala.collection.mutable.ListBuffer[String]()
    children.forEach { (child: CyElement) =>
      val cl = child.data("label")
      if (!js.isUndefined(cl) && cl != null) childNames += cl.asInstanceOf[String]
    }
    if (childNames.nonEmpty) {
      val childSection = detailsSection(panel, s"Members (${childNames.size})")
      for (name <- childNames.sorted) {
        val item = dom.document.createElement("div")
        item.textContent = name
        item.setAttribute("style", "padding:2px 0;")
        childSection.appendChild(item)
      }
    }
  }

  // ── Collapse / Expand ──

  private def collapseNode(cy: CyInstance, groupId: String): Unit = {
    val parent = cy.getElementById(groupId)
    if (parent.hasClass("collapsed")) return
    val children = parent.descendants()
    val childEdges = children.connectedEdges()

    val childIds = scala.collection.mutable.Set[String]()
    children.forEach((el: CyElement) => childIds += el.id())

    val metaEdges = js.Array[js.Object]()
    val seen = scala.collection.mutable.Set[String]()
    childEdges.forEach { (edge: CyElement) =>
      val srcId = edge.data("source").asInstanceOf[String]
      val tgtId = edge.data("target").asInstanceOf[String]
      val srcInside = childIds.contains(srcId)
      val tgtInside = childIds.contains(tgtId)

      if (srcInside != tgtInside) {
        val metaSrc = if (srcInside) groupId else srcId
        val metaTgt = if (tgtInside) groupId else tgtId
        val key = s"$metaSrc->$metaTgt"
        if (seen.add(key)) {
          metaEdges.push(js.Dynamic.literal(
            group = "edges",
            data = js.Dynamic.literal(
              id = s"meta_${groupId}_${edge.id()}",
              source = metaSrc,
              target = metaTgt,
              metaGroup = groupId,
            ),
            classes = "metaEdge",
          ).asInstanceOf[js.Object])
        }
      }
    }

    val _1 = children.hide()
    val _2 = childEdges.hide()
    if (metaEdges.length > 0) {
      val _3 = cy.add(metaEdges)
    }
    val _4 = parent.addClass("collapsed")
  }

  private def expandNode(cy: CyInstance, groupId: String): Unit = {
    val parent = cy.getElementById(groupId)
    if (!parent.hasClass("collapsed")) return
    val children = parent.descendants()

    val _1 = cy.remove(s"edge.metaEdge[metaGroup = '$groupId']")
    val _2 = children.show()
    val _3 = children.connectedEdges().show()
    val _4 = parent.removeClass("collapsed")
  }

  private def collapseAll(cy: CyInstance, groupIds: Set[String]): Unit = {
    val fn: js.Function0[Unit] = () => { for (gid <- groupIds) collapseNode(cy, gid) }
    cy.batch(fn)
  }

  private def expandAll(cy: CyInstance, groupIds: Set[String]): Unit = {
    val fn: js.Function0[Unit] = () => { for (gid <- groupIds) expandNode(cy, gid) }
    cy.batch(fn)
  }

  // ── Expand / Collapse folded resource nodes ──

  private def expandResource(cy: CyInstance, nodeId: String, data: LineageData): Unit = {
    val node = cy.getElementById(nodeId)
    if (node.hasClass("resourceExpanded")) return

    val config    = data.resourceDisplayConfig
    val segLabels = data.segmentLabels

    // Find the resources that belong to this folded node
    val resourceKey = {
      val rk = node.data("resourceKey")
      if (js.isUndefined(rk) || rk == null) "" else rk.asInstanceOf[String]
    }
    val resources = data.resources.filter { r =>
      GraphBuilder.foldedNodeKey(r.segments, r.resourceType, config) == resourceKey
    }
    if (resources.isEmpty) return

    // Add child resource nodes
    val children = GraphBuilder.buildFoldedChildren(nodeId, resources, config, segLabels)
    val _1 = cy.add(children)

    // Build lookup: resource key → child node ID
    val keyToChildId: Map[String, String] = resources.map { r =>
      r.key -> GraphBuilder.sanitizeNodeId(r.key)
    }.toMap

    // Rewire edges: hide original edges to folded node, create new edges to children
    val newEdges = js.Array[js.Object]()
    node.connectedEdges().forEach { (edge: CyElement) =>
      val edgeType = {
        val et = edge.data("edgeType")
        if (js.isUndefined(et) || et == null) "" else et.asInstanceOf[String]
      }
      if (edgeType == "integration") {
        val src = edge.data("source").asInstanceOf[String]
        val tgt = edge.data("target").asInstanceOf[String]
        val classId = if (src == nodeId) tgt else src
        val access = {
          val at = edge.data("accessType")
          if (js.isUndefined(at) || at == null) "" else at.asInstanceOf[String]
        }

        // Find which child resources this class connects to
        val classIntegrations = data.integrations.filter { i =>
          val cid = s"cls_${i.method.packageName.hashCode.abs}_${i.method.className}"
          cid == classId && GraphBuilder.foldedNodeKey(i.segments, i.resourceType, config) == resourceKey
        }

        // Group by child resource, create one edge per child
        val byChild = classIntegrations.groupBy(_.resourceKey)
        for ((resKey, ints) <- byChild) {
          keyToChildId.get(resKey).foreach { childId =>
            val combined = GraphBuilder.combineAccess(ints.map(_.accessType).toList)
            val label = combined match {
              case a => GraphBuilder.accessLabel(a)
            }
            val (edgeSrc, edgeTgt) = if (src == nodeId) (childId, tgt) else (src, childId)
            newEdges.push(js.Dynamic.literal(
              group = "edges",
              data = js.Dynamic.literal(
                id = s"rexp_${edgeSrc}_$edgeTgt",
                source = edgeSrc,
                target = edgeTgt,
                label = label,
                edgeType = "integration",
                accessType = combined,
                expandedFrom = nodeId,
              ),
              classes = s"integrationEdge $combined resourceExpandedEdge",
            ).asInstanceOf[js.Object])
          }
        }

        // Hide original edge
        edge.addClass("resourceFoldedHidden")
        val _h = edge.hide()
      }
    }

    if (newEdges.length > 0) {
      val _2 = cy.add(newEdges)
    }

    node.addClass("resourceExpanded")
    node.addClass("compound")
  }

  private def collapseResource(cy: CyInstance, nodeId: String): Unit = {
    val node = cy.getElementById(nodeId)
    if (!node.hasClass("resourceExpanded")) return

    // Remove child nodes and expanded edges
    val _1 = cy.remove(s"node[expandedFrom = '$nodeId']")
    val _2 = cy.remove(s"edge[expandedFrom = '$nodeId']")

    // Restore hidden original edges — use selector since connectedEdges() skips hidden edges
    cy.edges(".resourceFoldedHidden").forEach { (edge: CyElement) =>
      val src = edge.data("source").asInstanceOf[String]
      val tgt = edge.data("target").asInstanceOf[String]
      if (src == nodeId || tgt == nodeId) {
        edge.removeClass("resourceFoldedHidden")
        val _a = edge.show()
      }
    }

    node.removeClass("resourceExpanded")
    node.removeClass("compound")
  }

  // ── Expand / Collapse class into methods ──

  private val methodAccessColors = Map(
    "Read" -> "#d4edda", "Write" -> "#f8d7da", "ReadWrite" -> "#fff3cd", "Pure" -> "#ffffff",
  )
  private val methodAccessBorders = Map(
    "Read" -> "#28a745", "Write" -> "#dc3545", "ReadWrite" -> "#ffc107", "Pure" -> "#cccccc",
  )

  /** Check if a Cytoscape element ID actually exists in the graph. */
  private def nodeExists(cy: CyInstance, id: String): Boolean = {
    val col = cy.`$`(s"#$id")
    col.length > 0
  }

  /** Resolve a node ID to a visible target — if the node is hidden inside
    * a collapsed group, return the outermost collapsed ancestor ID instead. */
  private def resolveVisibleTarget(cy: CyInstance, id: String): Option[String] = {
    if (!nodeExists(cy, id)) return None
    var current = id
    var collapsed: Option[String] = None
    var done = false
    while (!done) {
      val node = cy.getElementById(current)
      val parentId = node.data("parent")
      if (js.isUndefined(parentId) || parentId == null) {
        done = true
      } else {
        val pid = parentId.asInstanceOf[String]
        val parent = cy.getElementById(pid)
        if (parent.hasClass("collapsed")) {
          collapsed = Some(pid)
        }
        current = pid
      }
    }
    Some(collapsed.getOrElse(id))
  }

  private def expandClass(cy: CyInstance, classNodeId: String, data: LineageData): Unit = {
    val classNode = cy.getElementById(classNodeId)
    if (classNode.hasClass("expanded")) return

    data.classes.find(_.classId == classNodeId) match {
      case None => ()
      case Some(classInfo) =>

    // Hide class-level edges AND meta-edges connected to this class
    classNode.connectedEdges().forEach { (edge: CyElement) =>
      edge.addClass("classLevelHidden")
      val _a = edge.hide()
    }
    // Also hide meta-edges where this class's parent group connects (if group is not collapsed)
    // and collect their targets for method-level reconnection

    // Add method nodes as children of the class node
    val methodNodes = js.Array[js.Object]()
    val visibleMethods = classInfo.methods.filter { m =>
      data.callGraph.exists(e => e.caller == m.ref || e.callee == m.ref) ||
      data.integrations.exists(i =>
        i.method.packageName == m.ref.packageName &&
          i.method.className == m.ref.className &&
          i.method.methodName == m.ref.methodName,
      )
    }
    val methodRefIds = visibleMethods.map(m => s"m_${m.ref.id}").toSet

    for (m <- visibleMethods) {
      val mid = s"m_${m.ref.id}"
      val bg = methodAccessColors.getOrElse(m.effectiveAccess, "#ffffff")
      val border = methodAccessBorders.getOrElse(m.effectiveAccess, "#cccccc")
      methodNodes.push(js.Dynamic.literal(
        group = "nodes",
        data = js.Dynamic.literal(
          id = mid,
          label = m.ref.methodName,
          parent = classNodeId,
          nodeType = "method",
          bg = bg,
          borderColor = border,
          accessType = m.effectiveAccess,
          expandedFrom = classNodeId,
        ),
        classes = s"methodNode ${m.effectiveAccess}",
      ).asInstanceOf[js.Object])
    }
    val _1 = cy.add(methodNodes)

    // Add method-level call edges (within or across expanded classes)
    val methodEdges = js.Array[js.Object]()
    val seen = scala.collection.mutable.Set[String]()
    for (edge <- data.callGraph) {
      val callerId = s"m_${edge.caller.id}"
      val calleeId = s"m_${edge.callee.id}"
      val callerExpanded = methodRefIds.contains(callerId)
      val calleeExpanded = methodRefIds.contains(calleeId)
      if (callerExpanded || calleeExpanded) {
        // Resolve each end to the best visible node
        val rawSrcId = if (callerExpanded) callerId else {
          val otherMethodId = s"m_${edge.caller.id}"
          if (nodeExists(cy, otherMethodId)) otherMethodId else edge.caller.classId
        }
        val rawTgtId = if (calleeExpanded) calleeId else {
          val otherMethodId = s"m_${edge.callee.id}"
          if (nodeExists(cy, otherMethodId)) otherMethodId else edge.callee.classId
        }
        val srcOpt = resolveVisibleTarget(cy, rawSrcId)
        val tgtOpt = resolveVisibleTarget(cy, rawTgtId)
        (srcOpt, tgtOpt) match {
          case (Some(srcId), Some(tgtId)) if srcId != tgtId =>
            val key = s"$srcId->$tgtId"
            if (seen.add(key)) {
              methodEdges.push(js.Dynamic.literal(
                group = "edges",
                data = js.Dynamic.literal(
                  id = s"mcall_${srcId}_$tgtId",
                  source = srcId,
                  target = tgtId,
                  edgeType = "methodCall",
                  expandedFrom = classNodeId,
                ),
                classes = "callEdge methodEdge",
              ).asInstanceOf[js.Object])
            }
          case _ => ()
        }
      }
    }

    // Add method-level integration edges
    for (integ <- data.integrations) {
      val mid = s"m_${integ.method.packageName.hashCode.abs}_${integ.method.className}_${integ.method.methodName}"
      if (methodRefIds.contains(mid)) {
        val resId = s"res_${integ.target}"
        resolveVisibleTarget(cy, resId) match {
          case Some(resolvedTarget) =>
            val key = s"$mid->$resolvedTarget"
            if (seen.add(key)) {
              val label = integ.accessType match {
                case a => GraphBuilder.accessLabel(a)
              }
              methodEdges.push(js.Dynamic.literal(
                group = "edges",
                data = js.Dynamic.literal(
                  id = s"mint_${mid}_$resolvedTarget",
                  source = mid,
                  target = resolvedTarget,
                  label = label,
                  edgeType = "methodIntegration",
                  accessType = integ.accessType,
                  expandedFrom = classNodeId,
                ),
                classes = s"integrationEdge methodEdge ${integ.accessType}",
              ).asInstanceOf[js.Object])
            }
          case None => ()
        }
      }
    }

    if (methodEdges.length > 0) {
      val _2 = cy.add(methodEdges)
    }
    val _3 = classNode.addClass("expanded")

    // Run layout on the expanded class and its neighborhood
    val expandedElements = cy.`$`(s"node[expandedFrom = '$classNodeId'], edge.methodEdge[expandedFrom = '$classNodeId'], #$classNodeId")
    val layoutOpts = js.Dynamic.literal(
      name = "fcose",
      animate = false,
      quality = "default",
      randomize = true,
      nodeSeparation = 40,
      idealEdgeLength = 80,
    )
    val layout = expandedElements.layout(layoutOpts.asInstanceOf[js.Object])
    val _4 = layout.run()

    } // end Some(classInfo)
  }

  private def collapseClass(cy: CyInstance, classNodeId: String): Unit = {
    val classNode = cy.getElementById(classNodeId)
    if (!classNode.hasClass("expanded")) return

    // Remove method nodes and method-level edges
    val _1 = cy.remove(s"node[expandedFrom = '$classNodeId']")
    val _2 = cy.remove(s"edge.methodEdge[expandedFrom = '$classNodeId']")

    // Restore class-level edges
    classNode.connectedEdges().forEach { (edge: CyElement) =>
      if (edge.hasClass("classLevelHidden")) {
        edge.removeClass("classLevelHidden")
        val _a = edge.show()
      }
    }
    val _3 = classNode.removeClass("expanded")
  }

  // ── Styles ──

  private val cytoscapeStyle: js.Array[js.Object] = js.Array(
    // Class nodes
    js.Dynamic.literal(
      selector = "node.classNode",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "center",
        `text-halign` = "center",
        `font-size` = "12px",
        `font-weight` = "500",
        `background-color` = "data(bg)",
        `border-width` = 2,
        `border-color` = "data(borderColor)",
        shape = "roundrectangle",
        width = "label",
        height = 30,
        padding = "10px",
        `text-wrap` = "none",
      ),
    ).asInstanceOf[js.Object],

    // Resource nodes
    js.Dynamic.literal(
      selector = "node.resourceNode",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "center",
        `text-halign` = "center",
        `font-size` = "11px",
        `background-color` = "data(bg)",
        `border-width` = 2,
        `border-color` = "data(borderColor)",
        shape = "ellipse",
        width = "label",
        height = 30,
        padding = "10px",
      ),
    ).asInstanceOf[js.Object],

    // Method nodes (inside expanded classes)
    js.Dynamic.literal(
      selector = "node.methodNode",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "center",
        `text-halign` = "center",
        `font-size` = "10px",
        `font-weight` = "400",
        `font-family` = "monospace",
        `background-color` = "data(bg)",
        `border-width` = 1,
        `border-color` = "data(borderColor)",
        shape = "roundrectangle",
        width = "label",
        height = 24,
        padding = "8px",
        `text-wrap` = "none",
      ),
    ).asInstanceOf[js.Object],

    // Expanded class nodes (become compound parents)
    js.Dynamic.literal(
      selector = "node.classNode.expanded",
      style = js.Dynamic.literal(
        `text-valign` = "top",
        `text-halign` = "center",
        `font-size` = "12px",
        `font-weight` = "600",
        `text-margin-y` = -5,
        `background-color` = "data(bg)",
        `background-opacity` = 0.3,
        `border-width` = 2,
        `border-color` = "data(borderColor)",
        `border-style` = "solid",
        shape = "roundrectangle",
        padding = "20px",
      ),
    ).asInstanceOf[js.Object],

    // Resource nodes by type
    js.Dynamic.literal(
      selector = "node.kafka",
      style = js.Dynamic.literal(shape = "roundrectangle", `border-style` = "dashed"),
    ).asInstanceOf[js.Object],

    js.Dynamic.literal(
      selector = "node.grpc",
      style = js.Dynamic.literal(shape = "diamond"),
    ).asInstanceOf[js.Object],

    js.Dynamic.literal(
      selector = "node.s3",
      style = js.Dynamic.literal(shape = "barrel"),
    ).asInstanceOf[js.Object],

    // Compound (group) nodes
    js.Dynamic.literal(
      selector = "node.classGroup",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "top",
        `text-halign` = "center",
        `font-size` = "13px",
        `font-weight` = "600",
        `text-margin-y` = -5,
        `background-color` = "rgba(240, 240, 240, 0.7)",
        `border-width` = 1,
        `border-color` = "#ddd",
        `border-style` = "solid",
        shape = "roundrectangle",
        padding = "20px",
      ),
    ).asInstanceOf[js.Object],

    js.Dynamic.literal(
      selector = "node.resourceGroup",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "top",
        `text-halign` = "center",
        `font-size` = "12px",
        `font-weight` = "600",
        `text-margin-y` = -5,
        `background-color` = "rgba(240, 248, 255, 0.7)",
        `border-width` = 1,
        `border-color` = "#aaa",
        `border-style` = "dashed",
        shape = "roundrectangle",
        padding = "20px",
      ),
    ).asInstanceOf[js.Object],

    // Expanded folded resource — render like a resource group
    js.Dynamic.literal(
      selector = "node.resourceExpanded",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "top",
        `text-halign` = "center",
        `font-size` = "11px",
        `font-weight` = "600",
        `text-margin-y` = -5,
        `background-color` = "data(bg)",
        `background-opacity` = 0.3,
        `border-width` = 1,
        `border-color` = "data(borderColor)",
        `border-style` = "dashed",
        shape = "roundrectangle",
        padding = "15px",
      ),
    ).asInstanceOf[js.Object],

    // Collapsed groups — render like a regular node
    js.Dynamic.literal(
      selector = "node.compound.collapsed",
      style = js.Dynamic.literal(
        `text-valign` = "center",
        `text-halign` = "center",
        `text-margin-y` = 0,
        `font-size` = "12px",
        `background-color` = "#e0e0e0",
        `background-opacity` = 1,
        `border-width` = 2,
        `border-color` = "#999",
        `border-style` = "solid",
        shape = "roundrectangle",
        `min-width` = 80,
        `min-height` = 30,
        padding = "30px",
      ),
    ).asInstanceOf[js.Object],

    // Selected nodes
    js.Dynamic.literal(
      selector = ".selected",
      style = js.Dynamic.literal(
        `border-width` = 4,
        `border-color` = "#4a90d9",
        `overlay-color` = "#4a90d9",
        `overlay-padding` = 4,
        `overlay-opacity` = 0.15,
      ),
    ).asInstanceOf[js.Object],

    // Unfocused elements (dimmed when focus is active)
    js.Dynamic.literal(
      selector = ".unfocused",
      style = js.Dynamic.literal(
        opacity = 0.1,
      ),
    ).asInstanceOf[js.Object],

    // Call edges
    js.Dynamic.literal(
      selector = "edge.callEdge",
      style = js.Dynamic.literal(
        `line-color` = "#999",
        `target-arrow-color` = "#999",
        `target-arrow-shape` = "triangle",
        `curve-style` = "taxi",
        width = 1,
        opacity = 0.6,
      ),
    ).asInstanceOf[js.Object],

    // Integration edges
    js.Dynamic.literal(
      selector = "edge.integrationEdge",
      style = js.Dynamic.literal(
        label = "data(label)",
        `font-size` = "10px",
        `line-color` = "#17a2b8",
        `target-arrow-color` = "#17a2b8",
        `target-arrow-shape` = "triangle",
        `curve-style` = "taxi",
        width = 2,
      ),
    ).asInstanceOf[js.Object],

    js.Dynamic.literal(
      selector = "edge.Read",
      style = js.Dynamic.literal(
        `line-color` = "#28a745",
        `target-arrow-color` = "#28a745",
        `line-style` = "dashed",
      ),
    ).asInstanceOf[js.Object],

    js.Dynamic.literal(
      selector = "edge.Write",
      style = js.Dynamic.literal(
        `line-color` = "#dc3545",
        `target-arrow-color` = "#dc3545",
      ),
    ).asInstanceOf[js.Object],

    js.Dynamic.literal(
      selector = "edge.ReadWrite",
      style = js.Dynamic.literal(
        `line-color` = "#ffc107",
        `target-arrow-color` = "#ffc107",
      ),
    ).asInstanceOf[js.Object],

    // Resource dependency edges
    js.Dynamic.literal(
      selector = "edge.resourceDepEdge",
      style = js.Dynamic.literal(
        `line-color` = "#aaa",
        `target-arrow-color` = "#aaa",
        `target-arrow-shape` = "triangle",
        `line-style` = "dashed",
        `curve-style` = "taxi",
        width = 1,
        opacity = 0.5,
      ),
    ).asInstanceOf[js.Object],

    // Meta-edges (shown when groups are collapsed)
    js.Dynamic.literal(
      selector = "edge.metaEdge",
      style = js.Dynamic.literal(
        `line-color` = "#666",
        `target-arrow-color` = "#666",
        `target-arrow-shape` = "triangle",
        `curve-style` = "taxi",
        width = 2,
        `line-style` = "dotted",
        opacity = 0.8,
      ),
    ).asInstanceOf[js.Object],
  )

  // ══════════════════════════════════════════════════════════════════════
  // Multi-service support
  // ══════════════════════════════════════════════════════════════════════

  /** Top-level component for multi-service mode.
    * Shows a service selector bar and either:
    *  - An individual service's detail view (same as single-service mode)
    *  - A "Connected" cross-service view
    */
  def multiServiceComponent(multi: MultiServiceData): HtmlElement = {
    // "connected" or a service name
    val activeService = Var("connected")
    val cyRef         = Var[Option[CyInstance]](None)

    div(
      // Service selector row
      div(
        span("Service: "),
        button(
          "Connected",
          cls <-- activeService.signal.map(s => if (s == "connected") "active" else ""),
          onClick --> { _ =>
            val prev = activeService.now()
            activeService.set("connected")
            if (prev != "connected") switchService(cyRef, "connected", multi)
          },
        ),
        multi.services.map { svc =>
          button(
            svc.name,
            cls <-- activeService.signal.map(s => if (s == svc.name) "active" else ""),
            onClick --> { _ =>
              val prev = activeService.now()
              activeService.set(svc.name)
              if (prev != svc.name) switchService(cyRef, svc.name, multi)
            },
          )
        },
      ),
      div(cls := "separator"),
      // Content placeholder — the service-specific controls are rendered dynamically into #controls
      // The actual graph switching is done imperatively via switchService
      onMountCallback { _ =>
        registerExtensions()
        switchService(cyRef, "connected", multi)
      },
    )
  }

  /** Switch between connected view and individual service views. */
  private def switchService(cyRef: Var[Option[CyInstance]], serviceName: String, multi: MultiServiceData): Unit = {
    // Destroy existing cytoscape instance
    cyRef.now().foreach { cy =>
      cy.asInstanceOf[js.Dynamic].destroy()
    }
    cyRef.set(None)

    // Hide mermaid if visible
    switchToCytoscape()

    // Clear the details pane
    closeDetails()

    // Remove any previous service-specific controls
    Option(dom.document.getElementById("service-controls")).foreach(_.remove())

    if (serviceName == "connected") {
      renderConnectedView(cyRef, multi)
    } else {
      multi.services.find(_.name == serviceName).foreach { svc =>
        renderServiceView(cyRef, svc)
      }
    }
  }

  /** Render the connected cross-service view using the same GraphBuilder infrastructure as service views. */
  private def renderConnectedView(cyRef: Var[Option[CyInstance]], multi: MultiServiceData): Unit = {
    val graphData = GraphBuilder.buildSystemLevel(multi.services)
    val container = dom.document.getElementById("cy")

    val cy = cytoscape(js.Dynamic.literal(
      container = container,
      elements = graphData.elements,
      style = connectedViewStyle,
      layout = js.Dynamic.literal(`type` = "preset"),
      minZoom = 0.05,
      maxZoom = 3.0,
      wheelSensitivity = 0.3,
    ).asInstanceOf[js.Object])

    cyRef.set(Some(cy))

    // Run ELK layout
    runLayout(cy, "elk-layered")

    // Merged data for side panel lookups
    val mergedData = mergeServiceData(multi)
    val activeLayout = Var("elk-layered")

    // Single-click → unified showDetails with cross-service context
    cy.on("tap", "node", { (evt: js.Dynamic) =>
      val node = evt.target.asInstanceOf[CyElement]
      showDetails(cy, node, mergedData, activeLayout, Some(multi), Some(graphData))
    }: js.Function1[js.Dynamic, Unit])

  }

  /** Expand a resource group: add child resource nodes + per-resource edges from expandableElements, remove aggregate edges.
    * When `sharedOnly` is true, only resources accessed by 2+ services are shown.
    */
  private def expandResourceGroup(cy: CyInstance, groupId: String, graphData: GraphBuilder.GraphData, sharedOnly: Boolean = false): Unit = {
    val groupNode = cy.getElementById(groupId)
    val allElements = graphData.expandableElements.getOrElse(groupId, js.Array[js.Object]())
    if (allElements.length == 0) return

    val sharedIds = graphData.sharedResourceIds.getOrElse(groupId, Set.empty)

    // Filter elements: keep nodes that pass the filter + edges whose target passes
    val filtered = if (!sharedOnly || sharedIds.isEmpty) allElements
    else {
      val result = js.Array[js.Object]()
      for (i <- 0 until allElements.length) {
        val el = allElements(i).asInstanceOf[js.Dynamic]
        val isEdge = el.group.asInstanceOf[String] == "edges"
        if (isEdge) {
          val tgt = el.data.target.asInstanceOf[String]
          if (sharedIds.contains(tgt)) result.push(allElements(i))
        } else {
          val nid = el.data.id.asInstanceOf[String]
          if (sharedIds.contains(nid)) result.push(allElements(i))
        }
      }
      result
    }

    if (filtered.length == 0) return

    cy.batch({ () =>
      // Remove aggregate edges from/to this group
      groupNode.connectedEdges().filter(".aggregatedEdge").remove()
      // Add children and per-resource edges
      cy.add(filtered)
      // Mark as expanded
      groupNode.removeClass("collapsedGroup")
      groupNode.addClass("compound")
      // Track mode
      if (sharedOnly) groupNode.addClass("sharedOnly") else groupNode.removeClass("sharedOnly")
    }: js.Function0[Unit])
  }

  /** Collapse a resource group: remove child nodes + per-resource edges, restore aggregate edges. */
  private def collapseResourceGroup(cy: CyInstance, groupId: String, graphData: GraphBuilder.GraphData): Unit = {
    val groupNode = cy.getElementById(groupId)

    cy.batch({ () =>
      // Remove all children and their edges
      groupNode.children().forEach { (child: CyElement) =>
        child.connectedEdges().remove()
      }
      groupNode.children().remove()
      // Restore aggregate edges from precomputed map
      for (edge <- graphData.aggregateEdges.getOrElse(groupId, js.Array())) cy.add(edge)
      // Mark as collapsed
      groupNode.addClass("collapsedGroup")
      groupNode.removeClass("compound")
    }: js.Function0[Unit])
  }

  /** Merge all services' LineageData into one for resource lookups in the system view side panel. */
  private def mergeServiceData(multi: MultiServiceData): LineageData = {
    val allResources = multi.services.flatMap(_.data.resources).groupBy(_.key).map(_._2.head).toList
    val allIntegrations = multi.services.flatMap(_.data.integrations)
    val config = multi.services.flatMap(_.data.resourceDisplayConfig.toList).toMap
    val segLabels = multi.services.flatMap(_.data.segmentLabels.toList).toMap
    LineageData(
      classes = Nil,
      callGraph = Nil,
      integrations = allIntegrations,
      resources = allResources,
      resourceDependencies = Nil,
      lineageChains = Nil,
      resourceDisplayConfig = config,
      segmentLabels = segLabels,
    )
  }

  /** Render a single service's detail view (reusing the single-service logic). */
  private def renderServiceView(cyRef: Var[Option[CyInstance]], svc: ServiceEntry): Unit = {
    val data = svc.data
    val codeViews   = ViewStore.fromCodeViews(data)
    val defaultView = if (codeViews.nonEmpty) codeViews.head else ViewStore.connectedOnlyView(data)
    val allClassIds = data.classes.map(_.classId).toSet
    val visibleClassIds = allClassIds -- defaultView.hiddenNodeIds

    val graph = GraphBuilder.buildClassLevel(data, visibleClassIds)

    // Create service-specific controls container
    val controlsDiv = dom.document.getElementById("controls")
    val serviceControlsDiv = dom.document.createElement("div")
    serviceControlsDiv.id = "service-controls"
    serviceControlsDiv.setAttribute("style", "display:contents;")
    controlsDiv.appendChild(serviceControlsDiv)

    val activeLayout    = Var("elk-layered")
    val activeCurve     = Var("taxi")
    val focusedNode     = Var[Option[String]](None)
    val dataFlow        = Var(true)
    val searchQuery     = Var("")
    val activeFilter    = Var("none")
    val builtInViews    = if (codeViews.nonEmpty) codeViews else List(defaultView)
    val savedViews      = Var(builtInViews ++ ViewStore.loadAll())
    val activeViewName  = Var(defaultView.name)
    val selectedNodeIds = Var(Set.empty[String])
    val viewMode        = Var("cytoscape")

    val cy = initCytoscape(graph, data, activeLayout, focusedNode, selectedNodeIds, savedViews, activeViewName)
    cyRef.set(Some(cy))
    applyView(cy, defaultView)
    flipEdges(cy, toDataFlow = true)
    runLayout(cy, "elk-layered")

    // Render Laminar sub-controls into the service-specific div
    val _ = com.raquo.laminar.api.L.render(serviceControlsDiv, serviceControlsComponent(
      cyRef, cy, data, defaultView, codeViews,
      activeLayout, activeCurve, focusedNode, dataFlow,
      searchQuery, activeFilter, savedViews, activeViewName, selectedNodeIds, viewMode,
    ))
  }

  /** The per-service controls (layout, edges, views, etc.) — extracted for reuse in multi-service mode. */
  private def serviceControlsComponent(
      @annotation.unused cyRef: Var[Option[CyInstance]],
      cy: CyInstance,
      data: LineageData,
      @annotation.unused defaultView: View,
      @annotation.unused codeViews: List[View],
      activeLayout: Var[String],
      activeCurve: Var[String],
      focusedNode: Var[Option[String]],
      dataFlow: Var[Boolean],
      searchQuery: Var[String],
      activeFilter: Var[String],
      savedViews: Var[List[View]],
      activeViewName: Var[String],
      @annotation.unused selectedNodeIds: Var[Set[String]],
      viewMode: Var[String],
  ): HtmlElement = {
    div(
      styleAttr := "display:contents;",
      // Renderer toggle
      div(
        span("Renderer: "),
        button(
          "Cytoscape",
          cls <-- viewMode.signal.map(m => if (m == "cytoscape") "active" else ""),
          onClick --> { _ =>
            viewMode.set("cytoscape")
            switchToCytoscape()
          },
        ),
        button(
          "Mermaid",
          cls <-- viewMode.signal.map(m => if (m == "mermaid") "active" else ""),
          onClick --> { _ =>
            viewMode.set("mermaid")
            val visibleIds = currentVisibleClassIds(Some(cy))
            switchToMermaid(data, visibleIds, dataFlow.now())
          },
        ),
      ),
      // Cytoscape-only controls
      div(
        display <-- viewMode.signal.map(m => if (m == "cytoscape") "contents" else "none"),
        div(
          span("Layout: "),
          layoutButtons.map { case (id, label) =>
            button(
              label,
              cls <-- activeLayout.signal.map(a => if (a == id) "active" else ""),
              onClick --> { _ =>
                runLayout(cy, id)
                activeLayout.set(id)
              },
            )
          },
          span(" | "),
          button("Re-layout", onClick --> { _ => runLayout(cy, activeLayout.now()) }),
        ),
        div(
          span("Edges: "),
          curveStyles.map { case (id, label) =>
            button(
              label,
              cls <-- activeCurve.signal.map(a => if (a == id) "active" else ""),
              onClick --> { _ =>
                setCurveStyle(cy, id)
                activeCurve.set(id)
              },
            )
          },
          span(" | Arrows: "),
          button(
            "Call Direction",
            cls <-- dataFlow.signal.map(df => if (!df) "active" else ""),
            onClick --> { _ =>
              if (dataFlow.now()) {
                flipEdges(cy, toDataFlow = false)
                dataFlow.set(false)
              }
            },
          ),
          button(
            "Data Flow",
            cls <-- dataFlow.signal.map(df => if (df) "active" else ""),
            onClick --> { _ =>
              if (!dataFlow.now()) {
                flipEdges(cy, toDataFlow = true)
                dataFlow.set(true)
              }
            },
          ),
          child <-- focusedNode.signal.map {
            case Some(nodeId) =>
              span(
                " | Focused: ", b(nodeId), " ",
                button("Clear Focus", onClick --> { _ =>
                  clearFocus(cy)
                  focusedNode.set(None)
                }),
              )
            case None => emptyNode
          },
        ),
        div(cls := "separator"),
        span("Search: "),
        input(
          typ := "text",
          placeholder := "Filter by name...",
          controlled(
            value <-- searchQuery.signal,
            onInput.mapToValue --> { v =>
              searchQuery.set(v)
              applyFilter(cy, activeFilter.now(), v)
            },
          ),
        ),
        button("✕", onClick --> { _ =>
          searchQuery.set("")
          applyFilter(cy, activeFilter.now(), "")
        }),
        span(" | Filter: "),
        filterButtons.map { case (id, label) =>
          button(
            label,
            cls <-- activeFilter.signal.map(a => if (a == id) "active" else ""),
            onClick --> { _ =>
              val newFilter = if (activeFilter.now() == id) "none" else id
              activeFilter.set(newFilter)
              applyFilter(cy, newFilter, searchQuery.now())
            },
          )
        },
        div(cls := "separator"),
        span("View: "),
        child <-- savedViews.signal.combineWith(activeViewName.signal).map { case (views, active) =>
          span(
            views.map { v =>
              button(
                v.name,
                cls := (if (v.name == active) "active" else ""),
                onClick --> { _ =>
                  applyView(cy, v)
                  activeViewName.set(v.name)
                  runLayout(cy, activeLayout.now())
                },
              )
            },
          )
        },
      ),
    )
  }

  /** Cytoscape style for the connected cross-service view.
    * Includes styles for service nodes, resource hierarchy (groups + leaf nodes), and edges.
    * Resource styles mirror the service-view styles so both views look consistent.
    */
  private val connectedViewStyle: js.Array[js.Object] = js.Array(
    // Service nodes
    js.Dynamic.literal(
      selector = "node.serviceNode",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "center",
        `text-halign` = "center",
        `font-size` = "14px",
        `font-weight` = "600",
        `background-color` = "data(bg)",
        `border-width` = 3,
        `border-color` = "data(borderColor)",
        shape = "roundrectangle",
        width = "label",
        height = 40,
        padding = "16px",
        `text-wrap` = "none",
      ),
    ).asInstanceOf[js.Object],

    // Resource group compound nodes (same style as service view)
    js.Dynamic.literal(
      selector = "node.resourceGroup",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "top",
        `text-halign` = "center",
        `font-size` = "11px",
        `font-weight` = "600",
        `background-color` = "data(bg)",
        `background-opacity` = 0.3,
        `border-width` = 2,
        `border-color` = "data(borderColor)",
        shape = "roundrectangle",
        padding = "12px",
      ),
    ).asInstanceOf[js.Object],

    // Collapsed resource group nodes (render as regular nodes, not compound containers)
    js.Dynamic.literal(
      selector = "node.collapsedGroup",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "center",
        `text-halign` = "center",
        `font-size` = "12px",
        `font-weight` = "600",
        `background-color` = "data(bg)",
        `background-opacity` = 0.8,
        `border-width` = 2,
        `border-color` = "data(borderColor)",
        shape = "roundrectangle",
        width = "label",
        height = 30,
        padding = "12px",
      ),
    ).asInstanceOf[js.Object],

    // Resource leaf nodes
    js.Dynamic.literal(
      selector = "node.resourceNode",
      style = js.Dynamic.literal(
        label = "data(label)",
        `text-valign` = "center",
        `text-halign` = "center",
        `font-size` = "10px",
        `background-color` = "data(bg)",
        `border-width` = 2,
        `border-color` = "data(borderColor)",
        shape = "ellipse",
        width = "label",
        height = 25,
        padding = "8px",
      ),
    ).asInstanceOf[js.Object],

    // Resource type shapes
    js.Dynamic.literal(
      selector = "node.grpc",
      style = js.Dynamic.literal(shape = "diamond"),
    ).asInstanceOf[js.Object],
    js.Dynamic.literal(
      selector = "node.kafka",
      style = js.Dynamic.literal(shape = "roundrectangle", `border-style` = "dashed"),
    ).asInstanceOf[js.Object],
    js.Dynamic.literal(
      selector = "node.s3",
      style = js.Dynamic.literal(shape = "barrel"),
    ).asInstanceOf[js.Object],

    // Integration edges (service → resource)
    js.Dynamic.literal(
      selector = "edge.integrationEdge",
      style = js.Dynamic.literal(
        label = "data(label)",
        `font-size` = "10px",
        `line-color` = "#17a2b8",
        `target-arrow-color` = "#17a2b8",
        `target-arrow-shape` = "triangle",
        `curve-style` = "bezier",
        width = 2,
      ),
    ).asInstanceOf[js.Object],

    // Resource dependency edges
    js.Dynamic.literal(
      selector = "edge.resourceDepEdge",
      style = js.Dynamic.literal(
        `line-color` = "#999",
        `target-arrow-color` = "#999",
        `target-arrow-shape` = "triangle",
        `line-style` = "dashed",
        `curve-style` = "bezier",
        width = 1,
      ),
    ).asInstanceOf[js.Object],

    // Access type edge colors
    js.Dynamic.literal(
      selector = "edge.Read",
      style = js.Dynamic.literal(
        `line-color` = "#28a745",
        `target-arrow-color` = "#28a745",
        `line-style` = "dashed",
      ),
    ).asInstanceOf[js.Object],
    js.Dynamic.literal(
      selector = "edge.Write",
      style = js.Dynamic.literal(
        `line-color` = "#dc3545",
        `target-arrow-color` = "#dc3545",
      ),
    ).asInstanceOf[js.Object],
    js.Dynamic.literal(
      selector = "edge.ReadWrite",
      style = js.Dynamic.literal(
        `line-color` = "#ffc107",
        `target-arrow-color` = "#ffc107",
      ),
    ).asInstanceOf[js.Object],
  )
}
