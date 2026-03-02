# Architecture Documentation System — Design Document

## 1. Overview

This document describes the design for architecture documentation capabilities in domainDocs4s. The system enables:

1. **Service-level documentation** — each service defines its internal architecture, referencing real code symbols to
   keep docs in sync.
2. **External specification** — each service exports a JSON spec describing what it produces and consumes.
3. **System-level documentation** — a central tool aggregates external specs from all services and renders cross-service
   dependency diagrams.

The system produces interactive, embeddable documentation (similar to scaladoc/javadoc) using Mermaid for simple output
and Cytoscape.js for rich, interactive views.

---

## 2. Concepts

### 2.1 Service Flow

Each service defines **one comprehensive flow declaration** capturing all its significant internal and external
components and their relationships. This is the single source of truth for that service's architecture.

```
┌─ Service Flow ─────────────────────────────────────────┐
│                                                         │
│  ┌─ Core ───────┐   ┌─ Projections ────────┐          │
│  │ gRPC API ────┼──→│ Movement Projection ──┼──→ [Kafka Topic]
│  │  ↓           │   │ Balance Projection  ──┼──→ [DB Table]
│  │ Ledger Actor │   └──────────────────────-┘          │
│  │  ↓           │                                       │
│  │ Journal      │                                       │
│  └──────────────┘                                       │
│                                                         │
│  Consumes: [Rate Service gRPC], [Config Service gRPC]   │
└─────────────────────────────────────────────────────────┘
```

### 2.2 Views

Users define **views** — named filters on the full flow that focus on a particular area or aspect. Views make large
flows digestible without losing the single-source-of-truth property.

Examples: "Data Ingestion", "Kafka Pipeline", "Projection Layer", "External Dependencies".

### 2.3 External Spec

Derived from the service flow. Nodes not marked as `internal` constitute the service's **external spec** — what it
produces for and consumes from the outside world. This is serialized to JSON and uploaded to a central repository.

### 2.4 System View

A central aggregation tool reads external specs from all services, matches produced and consumed artifacts, and renders
system-level dependency graphs.

---

## 3. Core Model

### 3.1 Node Categories and Artifacts

Nodes are typed using a two-layer system:

1. **`NodeCategory`** — a small, fixed enum of fundamental architectural categories. Drives visual encoding (node shape
   in diagrams) and coarse filtering.
2. **`Artifact`** — an open trait that carries category + instance-specific identifying information. The library ships
   common implementations, but users freely define their own.

The name `Artifact` (rather than `NodeKind`) reflects that each instance describes a **specific** thing — e.g., a
particular Kafka topic or a particular database table — not just a type of thing.

```scala
package domaindocs4s.arch

/** Fundamental architectural categories. Fixed set — drives visual encoding. */
enum NodeCategory {
  case Api       // something that exposes an interface (gRPC, HTTP, GraphQL, ...)
  case Message   // asynchronous messaging (Kafka topic, RabbitMQ queue, SQS, ...)
  case Dataset   // persistent data (DB table, S3 object, data warehouse, spreadsheet, ...)
  case Compute   // processing logic (component, projection, job, cache, ...)
}

/**
 * Describes a specific artifact in the architecture.
 *
 * Each Artifact carries its category (for visualization) and a matchKey that
 * uniquely identifies it. The matchKey is used both as the node's identity and
 * for cross-service artifact matching.
 *
 * The library provides common implementations (see below), but users can define
 * their own by extending this trait — no need to modify library code.
 */
trait Artifact {
  def category: NodeCategory

  /**
   * Unique identifier for this artifact. Used as the Node's id and for
   * cross-service matching (a produced artifact matches a consumed artifact
   * with the same matchKey).
   *
   * Convention: "type-prefix:identifying-details"
   * Examples: "kafka:ledger.movements", "db:projections.ledger.movements"
   */
  def matchKey: String
}
```

#### Library-provided artifacts

The library ships implementations for common integration patterns. These are regular classes, not a sealed hierarchy —
users can always add more.

```scala
package domaindocs4s.arch

object Artifacts {

  // ── Api ─────────────────────────────────────────────────────
  case class GrpcEndpoint(serviceName: String, methodName: String) extends Artifact {
    val category = NodeCategory.Api
    val matchKey = s"grpc:$serviceName/$methodName"
  }

  case class HttpEndpoint(method: String, path: String) extends Artifact {
    val category = NodeCategory.Api
    val matchKey = s"http:$method:$path"
  }

  // ── Message ─────────────────────────────────────────────────
  case class KafkaTopic(topicName: String) extends Artifact {
    val category = NodeCategory.Message
    val matchKey = s"kafka:$topicName"
  }

  // ── Dataset ─────────────────────────────────────────────────
  case class DatabaseTable(database: String, schema: String, tableName: String) extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"db:$database.$schema.$tableName"
  }

  case class DatabaseView(database: String, schema: String, viewName: String) extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"dbview:$database.$schema.$viewName"
  }

  case class S3Location(bucket: String, prefix: String = "") extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"s3:$bucket/$prefix"
  }

  case class Spreadsheet(provider: String, id: String = "") extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"sheet:$provider:$id"
  }

  case class DataWarehouse(system: String, table: String) extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"dw:$system:$table"
  }

  case class Journal(name: String) extends Artifact {
    val category = NodeCategory.Dataset
    val matchKey = s"journal:$name"
  }

  // ── Compute ─────────────────────────────────────────────────
  case class Component(name: String) extends Artifact {
    val category = NodeCategory.Compute
    val matchKey = s"component:$name"
  }

  case class Projection(name: String) extends Artifact {
    val category = NodeCategory.Compute
    val matchKey = s"projection:$name"
  }

  case class Job(name: String) extends Artifact {
    val category = NodeCategory.Compute
    val matchKey = s"job:$name"
  }

  case class Cache(name: String) extends Artifact {
    val category = NodeCategory.Compute
    val matchKey = s"cache:$name"
  }
}
```

#### User-defined artifacts (example)

```scala
// In user's codebase — no library changes needed
case class PubSubTopic(projectId: String, topicName: String) extends Artifact {
  val category = NodeCategory.Message
  val matchKey = s"pubsub:$projectId/$topicName"
}

case class RedisCache(cluster: String, keyPattern: String) extends Artifact {
  val category = NodeCategory.Dataset
  val matchKey = s"redis:$cluster:$keyPattern"
}
```

### 3.2 Node

```scala
case class Node(
    label: String,
    artifact: Artifact,
    internal: Boolean = true, // true = not part of external spec
) {
  /** Derived from artifact.matchKey — no separate id needed. */
  def id: String = artifact.matchKey
}
```

The `internal` flag controls whether a node appears in the external spec. By default, everything is internal — users
explicitly mark nodes as externally visible by setting `internal = false` (or via the `.exposed` DSL method).

The node's **id is derived from `artifact.matchKey`**, eliminating the redundant `id` parameter. Since `matchKey`
uniquely identifies an artifact (e.g., `"kafka:ledger.movements"`), it naturally serves as the node identity both within
a flow and for cross-service matching.

### 3.3 Edge

Edges have two types: **produces** and **consumes**. These cover the fundamental relationships in a data architecture.

```scala
enum EdgeType {
  case Produces  // from writes/pushes/creates data in to
  case Consumes  // from reads/pulls/uses data from to
}

case class Edge(from: Node, edgeType: EdgeType, to: Node, label: String = "")
```

Semantics:
- `A Produces B` — A writes data to B. Example: projection produces records in a DB table.
- `A Consumes B` — A reads data from B. Example: projection consumes events from a journal.
- For Compute → Compute edges (e.g., API calls an actor), `Produces` represents invocation direction (caller produces
  a request consumed by callee). This is a simplification that works for visualization; if a third edge type (e.g.,
  `Invokes`) proves necessary, it can be added later.

### 3.4 Subgraph

```scala
case class Subgraph(
    label: String,
    nodes: List[Node],
)
```

Subgraphs provide visual grouping. They don't affect semantics.

### 3.5 FlowChart (the full service declaration)

```scala
case class FlowChart(
    edges: List[Edge],
    subgraphs: List[Subgraph] = Nil,
) {
  lazy val nodes: List[Node] = // deduplicated from edges, preserving order

  /** Derive the external spec from this flow. */
  def externalSpec(serviceId: String, serviceName: String): ExternalSpec

  /** Create a filtered view of this flow. */
  def view(name: String): ViewBuilder
}
```

---

## 4. DSL

The DSL extends what already exists in the ledger service prototype, adding typed artifact constructors and the
`internal` flag.

### 4.1 Node Construction

```scala
package domaindocs4s.arch

import scala.reflect.ClassTag

// From a class — matchKey derived from class name, label from camelCase splitting
def component[T](using ct: ClassTag[T]): Node
def component[T](label: String)(using ct: ClassTag[T]): Node

// Typed constructors for common artifact types
def kafkaTopic(topicName: String, label: String = ""): Node
def dbTable(database: String, schema: String, tableName: String, label: String = ""): Node
def dbView(database: String, schema: String, viewName: String, label: String = ""): Node
def grpcEndpoint(serviceName: String, methodName: String, label: String = ""): Node
def httpEndpoint(method: String, path: String, label: String = ""): Node
def s3Location(bucket: String, prefix: String = "", label: String = ""): Node
def spreadsheet(provider: String, id: String = "", label: String = ""): Node
def journal(name: String, label: String = ""): Node
def cache(name: String, label: String = ""): Node
def job[T](using ct: ClassTag[T]): Node

// Generic fallback
def node(label: String, artifact: Artifact): Node

// Mark a node as externally visible
extension (n: Node) {
  def exposed: Node // sets internal = false
}
```

Note: the `id` parameter is gone from DSL constructors. The node's identity comes from the artifact's `matchKey`. The
`label` parameter defaults to a human-readable derivation from the artifact fields (e.g., `KafkaTopic("ledger.movements")`
defaults to label `"ledger.movements"`).

### 4.2 Edge Construction

```scala
extension (n: Node) {
  infix def produces(to: Node): Edge = Edge(n, EdgeType.Produces, to)
  infix def consumes(to: Node): Edge = Edge(n, EdgeType.Consumes, to)
}

extension (e: Edge) {
  infix def label(text: String): Edge = e.copy(label = text)
}
```

Usage:

```scala
ledgerActor produces journal,
dailyBalanceChange consumes journal,
movementsProjection produces movementsTable,
grpcApi consumes rateService,
ledgerDataService consumes ledgerOperatorClosingTable label "daily deltas",
```

### 4.3 Subgraph Construction

```scala
def subgraph(label: String)(nodes: Node*): Subgraph
```

### 4.4 Views

```scala
class ViewBuilder(name: String, source: FlowChart) {
  def only(nodes: Node*): ViewBuilder       // include only these nodes + edges between them
  def exclude(nodes: Node*): ViewBuilder     // exclude these nodes
  def build: FlowChart                       // produce filtered FlowChart
}
```

---

## 5. Scanners

### 5.1 Design Principles

Scanning has **two distinct faces**:

- **Consumed artifacts**: Scanning is authoritative. If the code calls a gRPC client, that's a real dependency. Scanner
  output for consumed artifacts is correct by definition.
- **Produced artifacts**: Scanning discovers what exists, but not everything produced is meant for external consumption.
  Scanner output for produced artifacts feeds into the manual flow declaration; the user curates what to expose.

There are **two kinds of scanners**:

- **Code scanners** — TASTy-based, for patterns in compiled Scala code (gRPC clients, Kafka producers, etc.)
- **Resource scanners** — file-based, for things TASTy can't see (SQL migrations, HOCON config, etc.)

### 5.2 Common Types

```scala
package domaindocs4s.arch.scanner

/** A discovered artifact from scanning the codebase. */
case class DiscoveredArtifact(
    artifact: Artifact,
    evidence: String,     // where it was found (symbol path, file:line, etc.)
    direction: Direction,
)

enum Direction {
  case Consumed
  case Produced
}
```

### 5.3 Code Scanners (TASTy-based)

Code scanners discover artifacts by inspecting compiled Scala symbols. The library provides the TASTy traversal
infrastructure — scanner implementations only express **what to look for**, not how to walk the AST.

```scala
package domaindocs4s.arch.scanner

import tastyquery.Symbols.*

/**
 * A code scanner that runs over TASTy symbols.
 *
 * The library drives the TASTy traversal (via the existing TastyContext / Collector
 * infrastructure). The scanner receives each symbol and decides whether it's relevant.
 * Users never interact with tasty-query directly.
 */
trait CodeScanner {
  def name: String

  /**
   * Inspect a single symbol. Return discovered artifacts if this symbol is relevant,
   * or Nil if not. Called by the library for every symbol in the scanned packages.
   */
  def inspect(symbol: Symbol): List[DiscoveredArtifact]
}
```

The library reuses the same `TastyContext` and package traversal as the `@domainDoc` collector. Users register scanners
and call a single entry point:

```scala
val ctx = TastyContext.fromCurrentProcess()
val results: List[DiscoveredArtifact] =
  ArchScanner.scan(ctx, packages = List("com.swissborg.ledger"), scanners = List(
    Fs2GrpcClientScanner,
    DoobieTableScanner,
    // user-defined scanners go here too
  ))
```

Internally, `ArchScanner.scan` walks all symbols in the given packages (same traversal as the collector) and calls
each scanner's `inspect` for every symbol.

#### Built-in code scanners

| Scanner                | Detects                              | Direction | What `inspect` looks for                        |
|------------------------|--------------------------------------|-----------|-------------------------------------------------|
| `Fs2GrpcClientScanner` | gRPC client usages                  | Consumed  | Symbols extending `*Fs2Grpc` client traits      |
| `Fs2GrpcServerScanner` | gRPC service implementations        | Produced  | Symbols implementing `*Fs2Grpc` service traits  |
| `DoobieTableScanner`   | Doobie SQL table references         | Both      | `Fragment` / `Query` / `Update` with table refs |
| `SlickTableScanner`    | Slick table definitions             | Both      | `Table[_]` / `TableQuery` instances             |
| `Fs2KafkaScanner`      | Kafka topic configurations          | Both      | `ConsumerSettings` / `ProducerSettings`         |
| `SttpClientScanner`    | HTTP client calls                   | Consumed  | `basicRequest` / sttp URI patterns              |
| `S3ClientScanner`      | S3 bucket/key access                | Both      | AWS S3 SDK call patterns                        |

#### User-space code scanner example

Users write a small `inspect` function — no TASTy boilerplate:

```scala
object GoogleSheetsScanner extends CodeScanner {
  val name = "google-sheets"

  def inspect(symbol: Symbol): List[DiscoveredArtifact] = {
    if (extendsType(symbol, "com.example.gsheets.GoogleSheetClient"))
      List(DiscoveredArtifact(
        artifact = Spreadsheet("Google Sheets"),
        evidence = symbol.fullName.toString,
        direction = Direction.Consumed,
      ))
    else Nil
  }
}
```

### 5.4 Resource Scanners (file-based)

Config files (HOCON, YAML), SQL migrations (Flyway), and other resources carry information that TASTy can't see.
Resource scanners operate on files, not symbols.

```scala
trait ResourceScanner {
  def name: String

  /** Which files this scanner cares about (glob pattern). */
  def filePattern: String

  /** Inspect a single file. Return discovered artifacts or Nil. */
  def inspect(path: Path, content: String): List[DiscoveredArtifact]
}
```

#### Built-in resource scanners

| Scanner                   | Detects                       | Direction | File pattern            |
|---------------------------|-------------------------------|-----------|-------------------------|
| `FlywayMigrationScanner` | DB tables from SQL migrations | Produced  | `**/db/migration/*.sql` |
| `HoconKafkaTopicScanner` | Kafka topics from config      | Both      | `**/*.conf`             |

### 5.5 Unified Discovery Entry Point

Both scanner types feed into the same pipeline:

```scala
val results: List[DiscoveredArtifact] = ArchScanner.discover(
  tastyContext = TastyContext.fromCurrentProcess(),
  packages = List("com.swissborg.ledger"),
  codeScanners = List(Fs2GrpcClientScanner, DoobieTableScanner),
  resourceScanners = List(FlywayMigrationScanner),
  resourceRoots = List(Path.of("src/main/resources")),
)
```

The result is a `List[DiscoveredArtifact]` — programmatic access that users can inspect, filter, or pass to the
consistency checker. No automatic visualization; users decide how to use the data.

### 5.6 Scanner Integration Workflow

**Step 1: Discovery** — Run scanners, get `List[DiscoveredArtifact]`.

**Step 2: Manual curation** — The user writes their service flow declaration (see section 4), incorporating
scanner-discovered artifacts. The key workflow:

1. Run discovery, inspect the results programmatically
2. Write the manual flow, referencing the same class types the scanner found
3. Optionally, use a **consistency check** that compares the manual flow against scanner output and warns about:
    - Consumed artifacts found by scanner but missing from the flow (likely a real dependency you forgot)
    - Produced artifacts in the flow that the scanner didn't find (maybe stale docs)

```scala
val consistency: ConsistencyReport =
  ConsistencyChecker.check(manualFlow, results)

// consistency.missingConsumed: List[DiscoveredArtifact]  — scanner found, flow doesn't have
// consistency.extraProduced: List[Node]                   — flow has, scanner didn't find
// consistency.matched: List[(Node, DiscoveredArtifact)]   — in sync
```

The consistency check can be run as a **test** in CI — no sbt plugin needed:

```scala
class ArchDocConsistencyTest extends AnyFreeSpec {
  "all consumed dependencies are documented" in {
    val discovered = ArchScanner.discover(...)
    val flow = MyServiceFlow.flow
    val report = ConsistencyChecker.check(flow, discovered)
    assert(report.missingConsumed.isEmpty,
      s"Undocumented dependencies: ${report.missingConsumed}")
  }
}
```

**Step 3: Local visualization** — Generate Mermaid or Cytoscape.js output from the flow declaration:

```scala
// Mermaid (for embedding in Markdown docs)
val markdown = s"```mermaid\n${MermaidRenderer.render(MyServiceFlow.flow)}\n```"
Files.writeString(Path.of("docs/generated/architecture.md"), markdown)

// Cytoscape.js (standalone HTML)
val html = CytoscapeRenderer.render(MyServiceFlow.flow)
Files.writeString(Path.of("docs/generated/architecture.html"), html)
```

---

## 6. External Spec (JSON)

### 6.1 Derivation

The external spec is derived from the service flow chart:

1. Collect all nodes where `internal = false`
2. Classify as produced or consumed based on edge types (nodes that are targets of `Produces` edges from internal nodes
   are produced; nodes that are sources of `Consumes` edges to internal nodes are consumed)
3. Serialize to JSON

```scala
case class ExternalSpec(
    service: ServiceInfo,
    produced: List[Artifact],
    consumed: List[Artifact],
)

case class ServiceInfo(
    id: String,
    name: String,
)
```

The artifact's `matchKey` serves as its id in the JSON. No separate `id` or `metadata` fields needed — the artifact
type and its fields carry all necessary information.

### 6.2 JSON Format

```json
{
  "service": {
    "id": "financial-ledger-service",
    "name": "Financial Ledger Service"
  },
  "produced": [
    {
      "type": "kafka-topic",
      "topicName": "ledger.movements"
    },
    {
      "type": "database-table",
      "database": "operational_projections",
      "schema": "ledger",
      "tableName": "operator_closing_of_account"
    },
    {
      "type": "grpc-endpoint",
      "serviceName": "LedgerServiceAPI",
      "methodName": "GetUserBalance"
    },
    {
      "type": "grpc-endpoint",
      "serviceName": "LedgerServiceAPI",
      "methodName": "RecordUserDeposit"
    },
    {
      "type": "s3-location",
      "bucket": "ledger-exports",
      "prefix": "user-assets/"
    }
  ],
  "consumed": [
    {
      "type": "grpc-endpoint",
      "serviceName": "RateServiceAPI",
      "methodName": "GetRate"
    },
    {
      "type": "grpc-endpoint",
      "serviceName": "ConfigServiceAPI",
      "methodName": "GetCurrencyInventory"
    }
  ]
}
```

Note: gRPC consumptions are **per-endpoint** (per RPC method), not per-service. This gives precise dependency
tracking — service A might only use 2 of 20 methods on service B.

**Open question — proto package extraction**: The fs2-grpc scanner sees Java package names, which don't necessarily
match proto package names. For MVP, we use the service/method names from the generated Scala code. If finer matching
is needed (e.g., cross-referencing with proto definitions), a pluggable matching strategy can be added.

### 6.3 Upload Workflow

Each service's CI pipeline:

1. Compiles the service + docs module
2. Runs `sbt "docs/run --export-spec"` (or equivalent)
3. Uploads the resulting JSON to the central repository (via git commit or S3 upload)

The core library is **agnostic to the upload mechanism**. It generates the JSON file; the user provides CI
instructions (Makefile target, sbt task, etc.) to push it.

---

## 7. System-Level Aggregation

### 7.1 How It Works

A separate tool (or module) in the central repository:

1. **Reads** all service JSON specs from a directory (gathered by CI)
2. **Matches** artifacts across services: a produced artifact matches a consumed artifact when their `matchKey` values
   are equal (same type + same identifying fields). For example:
    - `KafkaTopic("ledger.movements")` produced by Ledger matches `KafkaTopic("ledger.movements")` consumed by
      Analytics
    - `GrpcEndpoint("LedgerServiceAPI", "GetBalances")` produced by Ledger matches the same consumed by Controlling
    - `DatabaseTable("operational_projections", "ledger", "operator_closing_of_account")` produced by Ledger matches
      the same consumed by Controlling
3. **Generates** a system-level flow chart where:
    - Each service is a subgraph
    - Shared artifacts (Kafka topics, DB tables, gRPC endpoints) appear as **nodes between services** (not collapsed
      into edges) — making the shared resources visible
    - Edges connect services to the shared artifacts via `Produces`/`Consumes`
4. **Reports** anomalies:
    - Consumed artifacts with no known producer (unknown dependency)
    - Produced artifacts with no known consumer (potentially unused)

### 7.2 System Flow Output

Example system-level rendering:

```
┌─ Ledger Service ─┐                        ┌─ Controlling Service ─┐
│                   │                        │                       │
│  (internal)  ─────┼── Produces ──→ [operator_closing_of_account] ←── Consumes ──┼── (internal)
│                   │                        │                       │
│  (internal)  ─────┼── Produces ──→ [LedgerServiceAPI/GetBalances] ←── Consumes ──┼── (internal)
│                   │                        │                       │
│  (internal)  ─────┼── Produces ──→ [kafka:ledger.movements] ──→ ???  (no consumer)
│                   │                        │                       │
│  (internal)  ─────┼── Consumes ──→ [RateServiceAPI/GetRate] ←── Consumes ──┼── (internal)
└───────────────────┘                        └───────────────────────┘
```

This system-level flow chart is rendered using the same renderers (Mermaid, Cytoscape.js).

---

## 8. Rendering

### 8.1 Renderer Trait

```scala
package domaindocs4s.arch.render

trait Renderer {
  /** Render a flow chart to a string (Mermaid markdown, HTML, etc.) */
  def render(chart: FlowChart): String
}
```

### 8.2 Mermaid Renderer

Extends the existing prototype. Enhancements:

- **Node shapes by category**: Dataset → `[(cylinder)]`, Message → `{{hexagon}}`, Api → `{diamond}`,
  Compute → `[rectangle]`
- **Edge styling**: `Produces` → solid arrow, `Consumes` → dashed arrow (or similar distinction)
- **Subgraph styling**: Different background colors per subgraph

Output is a Mermaid flowchart string embeddable in Markdown.

```scala
object MermaidRenderer extends Renderer {
  def render(chart: FlowChart): String = {
    // flowchart LR
    // subgraph Service["Service"]
    //   component_LedgerActor["Ledger Actor"]
    //   journal_pekko[("Pekko Journal")]          ← cylinder for Dataset
    // end
    // kafka_ledger_movements{{"ledger.movements"}} ← hexagon for Message
    ???
  }
}
```

### 8.3 Cytoscape.js Renderer — Rich Interactive Viewer

The Cytoscape.js renderer produces a **standalone HTML file** that can be:

- Opened directly in a browser
- Served by a local HTTP server (like scaladoc)
- Embedded as an iframe in a documentation site
- Published as static files to a hosting service

#### 8.3.1 Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Generated HTML                                          │
│                                                          │
│  ┌─ <head> ──────────────────────────────────────────┐  │
│  │  Cytoscape.js (from CDN)                           │  │
│  │  cytoscape-dagre (layout plugin, from CDN)         │  │
│  │  Viewer CSS                                        │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  ┌─ <body> ──────────────────────────────────────────┐  │
│  │                                                    │  │
│  │  ┌─ Toolbar ──────────────────────────────────┐   │  │
│  │  │ [Search...] [View: ▼ Full] [Fit] [PNG]     │   │  │
│  │  └────────────────────────────────────────────┘   │  │
│  │                                                    │  │
│  │  ┌─ Canvas ────────────┬─ Detail Panel ───────┐   │  │
│  │  │                     │                       │   │  │
│  │  │   ○──→□──→◇──→⬡    │  Node: movements     │   │  │
│  │  │       ↓             │  Category: Dataset    │   │  │
│  │  │   ○──→□──→⬡        │  Type: DB Table       │   │  │
│  │  │                     │  Database: projections│   │  │
│  │  │                     │  Table: movements     │   │  │
│  │  │                     │                       │   │  │
│  │  │                     │  Incoming:            │   │  │
│  │  │                     │  ← MovementProjection │   │  │
│  │  └─────────────────────┴───────────────────────┘   │  │
│  │                                                    │  │
│  │  ┌─ Legend ───────────────────────────────────┐   │  │
│  │  │ ○ Compute  □ Dataset  ◇ Message  ⬡ Api    │   │  │
│  │  │ ─── internal  ╌╌╌ exposed                  │   │  │
│  │  └────────────────────────────────────────────┘   │  │
│  │                                                    │  │
│  │  ┌─ <script> ─────────────────────────────────┐   │  │
│  │  │  const graphData = { /* JSON flow data */ } │   │  │
│  │  │  // Viewer initialization code              │   │  │
│  │  └────────────────────────────────────────────┘   │  │
│  │                                                    │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

#### 8.3.2 Features

**Navigation:**

- Pan and zoom with mouse/trackpad
- Fit-to-screen button
- Minimap for large graphs

**Selection & Inspection:**

- Click a node to select it; detail panel shows all artifact fields
- Click an edge to see its type (produces/consumes) and connected nodes
- Text search across node labels

**Layout:**

- Default: dagre (directed acyclic graph layout) — good for data pipelines
- Alternative layouts: breadthfirst, cose (force-directed), grid
- Subgraph-aware: nodes in the same subgraph cluster together

**Visual Encoding:**

- Node shape by category (4 shapes for 4 categories)
- Edge style by type (solid for Produces, dashed for Consumes)
- Subgraph → colored background region
- External (exposed) nodes have a distinct border style (e.g., thicker, colored)

**Export:**

- Export as PNG or SVG for embedding in presentations/wikis

**Views:**

- If the service defines named views, the toolbar includes a view selector dropdown
- Selecting a view filters the graph to show only relevant nodes/edges
- "Full" view is always available as the default

#### 8.3.3 Data Format (embedded JSON)

The flow chart is serialized into cytoscape.js-compatible JSON:

```json
{
  "elements": {
    "nodes": [
      {
        "data": {
          "id": "component:LedgerActor",
          "label": "Ledger Actor",
          "category": "compute",
          "internal": true,
          "parent": "Service"
        }
      },
      {
        "data": {
          "id": "Service",
          "label": "Service"
        },
        "classes": ["subgraph"]
      }
    ],
    "edges": [
      {
        "data": {
          "source": "component:grpcApi",
          "target": "component:LedgerActor",
          "edgeType": "produces"
        }
      }
    ]
  },
  "views": {
    "Data Ingestion": {
      "includeNodes": ["component:grpcApi", "component:sagaActors", "component:LedgerActor", "journal:pekko"]
    }
  }
}
```

#### 8.3.4 Implementation Approach

The Cytoscape.js renderer is a Scala object that:

1. Converts `FlowChart` → cytoscape.js JSON (as above)
2. Loads an HTML template from resources (`domaindocs4s/arch/viewer/template.html`)
3. Injects the JSON data and any configuration
4. Produces a self-contained HTML string

The HTML template loads Cytoscape.js and plugins from CDN. The viewer JavaScript (search, detail panel, export) and CSS
are embedded in the template.

```scala
object CytoscapeRenderer extends Renderer {
  def render(chart: FlowChart): String = {
    val json = toCytoscapeJson(chart)
    val template = loadTemplate("domaindocs4s/arch/viewer/template.html")
    template.replace("{{GRAPH_DATA}}", json)
  }

  /** Render with views included. */
  def renderWithViews(chart: FlowChart, views: Map[String, FlowChart]): String
}
```

#### 8.3.5 Multi-Page Documentation Site

For services with multiple views or large architectures, the renderer can produce a **multi-page static site**:

```
docs/
├── index.html          ← overview with links to all views
├── full.html           ← complete flow chart
├── data-ingestion.html ← "Data Ingestion" view
├── kafka-pipeline.html ← "Kafka Pipeline" view
├── glossary.html       ← domain glossary (from @domainDoc, if enabled)
└── assets/
    ├── viewer.js
    └── styles.css
```

---

## 9. Integration with Existing domainDocs4s

### 9.1 Package Structure

The architecture docs live in a separate package within the same module:

```
domaindocs4s-core/
├── src/main/scala/domaindocs4s/
│   ├── domain/          ← existing @domainDoc annotation
│   ├── collector/       ← existing TASTy collector
│   ├── output/          ← existing Glossary renderer
│   ├── arch/            ← NEW: architecture documentation
│   │   ├── model.scala       ← Node, Edge, FlowChart, Artifact, etc.
│   │   ├── dsl.scala         ← DSL functions
│   │   ├── ExternalSpec.scala
│   │   ├── scanner/
│   │   │   ├── CodeScanner.scala
│   │   │   ├── ResourceScanner.scala
│   │   │   ├── Fs2GrpcClientScanner.scala
│   │   │   └── ...
│   │   ├── render/
│   │   │   ├── Renderer.scala
│   │   │   ├── MermaidRenderer.scala
│   │   │   └── CytoscapeRenderer.scala
│   │   └── aggregation/
│   │       ├── SystemAggregator.scala
│   │       └── ConsistencyChecker.scala
├── src/main/resources/domaindocs4s/arch/viewer/
│   └── template.html
```

Everything stays in `domainDocs4s-core` for now. If heavy external dependencies are introduced later (e.g., a JSON
library for spec serialization), we can split then.

### 9.2 TASTy Scanning Reuse

The architecture scanners reuse the same `tasty-query` infrastructure as the domain docs collector. The `TastyContext`
and symbol traversal machinery is shared; scanners just look for different patterns (gRPC client instantiation rather
than `@domainDoc` annotations).

### 9.3 Glossary Integration

**Future possibility** (needs further exploration): architecture nodes could optionally link to `@domainDoc`-annotated
domain concepts — e.g., a DB table node linking to the documented domain model it stores. This could enable showing
glossary entries in the Cytoscape.js detail panel. Not designed in detail here; the current architecture is compatible
with adding this later.

---

## 10. Open Questions

1. **Scanner implementation depth**: TASTy-based scanners for gRPC/Doobie/Kafka require understanding each library's
   code patterns. How much investment in scanner accuracy vs. relying on manual declaration?

2. **NodeCategory completeness**: Are the four categories (Api, Message, Dataset, Compute) sufficient, or do we need an
   escape hatch (e.g., `Other`)? Deferring until real usage reveals gaps.

3. **Proto package extraction**: The fs2-grpc scanner sees Java package names, not proto package names. For MVP, use
   service/method names from generated code. If cross-referencing with proto definitions is needed later, add pluggable
   matching.

4. **Integration with existing `DataPipelineDoc.scala`**: The existing prototype in the ledger service uses the current
   `flow.*` DSL. Migration path: the new model is a superset; existing code can be migrated by replacing `Node` with
   typed artifacts and freeform edge labels with `produces`/`consumes`.

---

## 11. Example

See the companion example files in `domainDocs4s-examples/src/main/scala/domaindocs4s/architecture/` for a concrete,
simplified model based on the financial-ledger-service and controlling-service.
