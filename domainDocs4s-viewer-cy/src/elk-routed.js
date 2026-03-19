/**
 * Forked cytoscape-elk adapter that preserves ELK's edge routing (bend points)
 * and maps them to Cytoscape's `segments` curve-style.
 *
 * Registers as layout name "elk-routed".
 */
import ELK from 'elkjs/lib/elk.bundled.js';

const defaults = {
  nodeDimensionsIncludeLabels: false,
  fit: true,
  padding: 20,
  animate: false,
  animateFilter: () => true,
  animationDuration: 500,
  animationEasing: undefined,
  transform: (node, pos) => pos,
  ready: undefined,
  stop: undefined,
  nodeLayoutOptions: undefined,
  elk: {
    algorithm: undefined,
  },
  priority: () => null,
};

function assign(tgt, ...srcs) {
  for (const src of srcs) {
    if (src) {
      for (const key of Object.keys(src)) {
        tgt[key] = src[key];
      }
    }
  }
  return tgt;
}

const elkOverrides = {};

const getPos = function (ele, options) {
  const dims = ele.layoutDimensions(options);
  let parent = ele.parent();
  const k = ele.scratch('elk');
  const p = { x: k.x, y: k.y };

  while (parent.nonempty()) {
    const kp = parent.scratch('elk');
    p.x += kp.x;
    p.y += kp.y;
    parent = parent.parent();
  }

  // ELK top-left -> Cytoscape center
  p.x += dims.w / 2;
  p.y += dims.h / 2;

  return p;
};

/** Convert an ELK absolute point to Cytoscape absolute coords,
 *  accumulating parent offsets for the given edge's source node. */
const elkPointToAbsolute = function (point, edgeEle) {
  // ELK bend points are in the coordinate space of the edge's container.
  // For top-level edges, that's the root — no offset needed.
  // For edges inside compound nodes, we'd need the parent offset.
  // Since cytoscape-elk puts all edges at root level, points are absolute.
  let src = edgeEle.source();
  let parent = src.parent();
  const p = { x: point.x, y: point.y };

  // Walk up the parent chain to get absolute coordinates
  // (ELK points for root-level edges are already in root coords)
  return p;
};

const makeNode = function (node, options) {
  const k = {
    _cyEle: node,
    id: node.id(),
  };

  if (options.nodeLayoutOptions) {
    k.layoutOptions = options.nodeLayoutOptions(node);
  }

  if (!node.isParent()) {
    const dims = node.layoutDimensions(options);
    const p = node.position();
    k.x = p.x - dims.w / 2;
    k.y = p.y - dims.h / 2;
    k.width = dims.w;
    k.height = dims.h;
  }

  node.scratch('elk', k);
  return k;
};

const makeEdge = function (edge) {
  const k = {
    _cyEle: edge,
    id: edge.id(),
    source: edge.data('source'),
    target: edge.data('target'),
  };
  edge.scratch('elk', k);
  return k;
};

const makeGraph = function (nodes, edges, options) {
  const elkNodes = [];
  const elkEdges = [];
  const elkEleLookup = {};
  const graph = { id: 'root', children: [], edges: [] };

  for (let i = 0; i < nodes.length; i++) {
    const n = nodes[i];
    const k = makeNode(n, options);
    elkNodes.push(k);
    elkEleLookup[n.id()] = k;
  }

  for (let i = 0; i < edges.length; i++) {
    const e = edges[i];
    const k = makeEdge(e, options);
    elkEdges.push(k);
    elkEleLookup[e.id()] = k;
  }

  // Build hierarchy
  for (let i = 0; i < elkNodes.length; i++) {
    const k = elkNodes[i];
    const n = k._cyEle;
    if (!n.isChild()) {
      graph.children.push(k);
    } else {
      const parent = n.parent();
      const parentK = elkEleLookup[parent.id()];
      const children = (parentK.children = parentK.children || []);
      children.push(k);
    }
  }

  for (let i = 0; i < elkEdges.length; i++) {
    graph.edges.push(elkEdges[i]);
  }

  return graph;
};

/**
 * After ELK layout, extract bend points from edge sections and apply them
 * as Cytoscape `segments` curve-style control points.
 */
const applyEdgeRouting = function (edges, options) {
  edges.forEach(function (edge) {
    const elkData = edge.scratch('elk');
    if (!elkData) return;

    // ELK populates sections with routing data
    const sections = elkData.sections;
    if (!sections || sections.length === 0) return;

    const section = sections[0];
    const bendPoints = section.bendPoints;
    if (!bendPoints || bendPoints.length === 0) return;

    const src = edge.source();
    const tgt = edge.target();

    // Get absolute source/target positions (Cytoscape center coords)
    const srcPos = src.position();
    const tgtPos = tgt.position();

    // Convert ELK bend points to absolute coordinates
    // ELK points are in root coordinate space for root-level edges
    // We need to accumulate parent offsets for the source node's ancestry
    // to convert from ELK coords to Cytoscape coords
    let offsetX = 0, offsetY = 0;
    // Since all edges are at root level in the ELK graph, bend points
    // are already in root coordinates. No parent offset needed.

    const absBendPoints = bendPoints.map(bp => ({
      x: bp.x + offsetX,
      y: bp.y + offsetY,
    }));

    // Convert absolute bend points to segment-distances and segment-weights
    // relative to the source→target line
    const dx = tgtPos.x - srcPos.x;
    const dy = tgtPos.y - srcPos.y;
    const len2 = dx * dx + dy * dy;

    if (len2 < 1) return; // degenerate: src ≈ tgt

    const len = Math.sqrt(len2);
    // Unit vector along source→target
    const ux = dx / len;
    const uy = dy / len;
    // Perpendicular unit vector
    const px = -uy;
    const py = ux;

    const weights = [];
    const distances = [];

    for (const bp of absBendPoints) {
      const rx = bp.x - srcPos.x;
      const ry = bp.y - srcPos.y;
      // Weight: projection along src→tgt, normalized to [0,1]
      const w = (rx * ux + ry * uy) / len;
      // Distance: signed perpendicular offset
      const d = rx * px + ry * py;
      weights.push(w);
      distances.push(d);
    }

    // Apply as segments curve-style
    edge.style({
      'curve-style': 'segments',
      'segment-weights': weights.join(' '),
      'segment-distances': distances.join(' '),
      'edge-distances': 'node-position',
    });
  });
};

class Layout {
  constructor(options) {
    const elkOptions = options.elk;
    const { cy } = options;

    this.options = assign({}, defaults, options);
    this.options.elk = assign(
      { aspectRatio: cy.width() / cy.height() },
      defaults.elk,
      elkOptions,
      elkOverrides
    );
  }

  run() {
    const layout = this;
    const { options } = this;
    const { eles } = options;
    const nodes = eles.nodes();
    const edges = eles.edges();

    const elk = new ELK();
    const graph = makeGraph(nodes, edges, options);
    graph['layoutOptions'] = options.elk;

    elk.layout(graph).then(() => {
      nodes
        .filter((n) => !n.isParent())
        .layoutPositions(layout, options, (n) => getPos(n, options));

      // Apply edge routing from ELK bend points
      applyEdgeRouting(edges, options);
    });

    return this;
  }

  stop() {
    return this;
  }

  destroy() {
    return this;
  }
}

// Register as 'elk-routed' layout
const register = function (cytoscape) {
  if (!cytoscape) return;
  cytoscape('layout', 'elk-routed', Layout);
};

if (typeof cytoscape !== 'undefined') {
  register(cytoscape);
}

export default register;
