# Architecture Documentation System — Design Document

## 1. Overview

This document describes the design for architecture documentation capabilities in domainDocs4s. The system aims to:

1. **Automatic data lineage** — scan compiled code to discover database access patterns and trace them from API
   entry points through service layers to the actual queries. Working prototype.
2. **Service-level documentation** — each service defines its internal architecture, referencing real code symbols to
   keep docs in sync.
3. **External specification** — each service exports a JSON spec describing what it produces and consumes.
4. **System-level documentation** — a central tool aggregates external specs from all services and renders cross-service
   dependency diagrams.

The system produces interactive, embeddable documentation using Mermaid for simple output and Cytoscape.js for rich,
interactive views.

---

## 2. Status

| Component                    | Status         | Location                                          |
|------------------------------|----------------|---------------------------------------------------|
| Data lineage model           | Implemented    | `lineage/model.scala`                             |
| Doobie scanner (TASTy-based) | Implemented    | `lineage/TastyDoobieScanner.scala`                |
| Call graph extractor         | Implemented    | `lineage/TastyCallGraphExtractor.scala`            |
| Lineage builder              | Implemented    | `lineage/LineageBuilder.scala`                    |
| Mermaid visualization        | Implemented    | `lineage/MermaidRenderer.scala`                   |
| Example classes (real doobie)| Implemented    | `lineage/example/ExampleClasses.scala`            |
| Tests (9 tests passing)      | Implemented    | `lineage/TastyLineageScannerTest.scala`           |
| Service flow DSL             | Design only    | Section 6 below                                   |
| External spec / system view  | Design only    | Sections 8-9 below                                |
| Cytoscape.js renderer        | Design only    | Section 10.3 below                                |
| Other scanners (gRPC, Kafka) | Design only    | Section 7 below                                   |

All implemented files are under `domainDocs4s-examples/src/main/scala/domaindocs4s/architecture/`.

---

## 3. Data Lineage — Prototype

### 3.1 Architecture

The lineage system has three independent phases, each with its own output type. This lets us add new integration
scanners (kafka, gRPC, etc.) without changing the lineage builder.

```
Phase 0: Call Graph Extraction          Phase 1: Integration Scanning
┌──────────────────────────┐            ┌───────────────────────────┐
│  TastyCallGraphExtractor │            │    TastyDoobieScanner     │
│                          │            │                           │
│  TASTy ──→ List[         │            │  TASTy ──→ List[          │
│    ExtractedMethod       │            │    DiscoveredIntegration   │
│  ]                       │            │  ]                        │
│                          │            │                           │
│  Generic: any package    │            │  "classA.methodB          │
│  field.method() calls    │            │   reads/writes tableC"    │
└────────────┬─────────────┘            └─────────────┬─────────────┘
             │                                        │
             └──────────────┬─────────────────────────┘
                            ▼
                  Phase 2: Lineage Building
                  ┌─────────────────────────┐
                  │     LineageBuilder       │
                  │                          │
                  │  Combines call graph +   │
                  │  integrations ──→        │
                  │    ScanResult            │
                  │                          │
                  │  - Propagates R/W types  │
                  │  - Builds lineage chains │
                  │  - Entry point → DB      │
                  └────────────┬────────────┘
                               ▼
                     Phase 3: Rendering
                     ┌─────────────────┐
                     │ MermaidRenderer  │
                     │                  │
                     │ ScanResult ──→   │
                     │   mermaid.live   │
                     │   URL            │
                     └─────────────────┘
```

### 3.2 Model

Core types in `model.scala`:

```scala
enum DataAccessType { case Read, Write, ReadWrite, Pure }

case class MethodRef(className: String, methodName: String)

// Phase 0 output — generic call graph
case class ExtractedMethod(className: String, packageName: String, methodName: String, calls: List[MethodRef])

// Phase 1 output — scanner-specific integration discovery
case class DiscoveredIntegration(
    method: MethodRef,              // classA.methodB
    accessType: DataAccessType,     // reads or writes
    integrationType: String,        // "doobie", "kafka", "grpc", ...
    target: String,                 // table name, topic, endpoint, ...
    evidence: String,               // the actual SQL, config key, ...
)

// Phase 2 output — full lineage result
case class ScanResult(
    classes: List[ScannedClass],
    callGraph: List[CallEdge],
    integrations: List[DiscoveredIntegration],
    lineageChains: List[LineageChain],
)

case class LineageChain(
    entryPoint: MethodRef,          // API method where the chain starts
    path: List[MethodRef],          // full call path
    integration: DiscoveredIntegration, // the DB operation at the end
)
```

Key design decisions:
- `DiscoveredIntegration` is the contract any scanner must produce. Adding a Kafka scanner means
  producing `DiscoveredIntegration(integrationType = "kafka", target = "topic-name", ...)`.
- `LineageBuilder` is fully generic — it doesn't know about doobie, only about call graphs and integrations.
- Access types propagate recursively: if `Service.deposit` calls `Repo.updateBalance` (Write) and
  `Repo.insertTransaction` (Write), then `Service.deposit` is Write. If it called both reads and writes,
  it would be ReadWrite.

### 3.3 Doobie Scanner

`TastyDoobieScanner` scans compiled Scala code via tasty-query. It finds methods returning `ConnectionIO[_]`
then pattern-matches the TASTy AST for doobie query chains.

Detected patterns (real doobie with `sql"..."` interpolation):

| Code pattern                          | TASTy AST shape                                                       | Classification |
|---------------------------------------|-----------------------------------------------------------------------|----------------|
| `sql"...".query[T].unique`            | `Select(Apply(TypeApply(Select(frag, query), _), _), unique)`         | Read           |
| `sql"...".query[T].option`            | `Select(Apply(TypeApply(Select(frag, query), _), _), option)`         | Read           |
| `sql"...".query[T].to[List]`          | `Apply(TypeApply(Select(Apply(TypeApply(Select(frag, query), _), _), to), _), _)` | Read |
| `sql"...".update.run`                 | `Select(Select(frag, update), run)`                                   | Write          |

SQL extraction: the `sql"..."` interpolator produces a `StringContext.apply("part1", "part2", ...)` in the TASTy tree.
The scanner collects all string literal parts and joins them to recover the SQL template. Table names are extracted
via regex (`FROM \w+`, `INTO \w+`, `UPDATE \w+`, `DELETE FROM \w+`).

The `.query[T]` patterns have an extra `Apply` wrapping compared to naive implementations because real doobie's
`.query[T]` takes an implicit `Read[T]` parameter, which produces an additional `Apply` node in the TASTy tree.

### 3.4 Call Graph Extractor

`TastyCallGraphExtractor` is generic (not doobie-specific). It:

1. Finds all user-defined classes in a package (filters out synthetic `$` companions and `<init>`)
2. Resolves field types: `class UserService(val repo: UserRepo)` → `repo: UserRepo`
3. Walks method bodies looking for `field.method(...)` patterns in the TASTy tree
4. Maps `repo.getBalance(...)` → `MethodRef("UserRepo", "getBalance")` using the resolved field types

This uses `TreeTraverser` from tasty-query which recursively visits all sub-trees, including lambda
bodies from for-comprehension desugaring (`flatMap`, `map`).

### 3.5 Lineage Builder

`LineageBuilder.build(callGraph, integrations)` combines both phases:

1. Assigns direct access types from scanner output (Repo methods get Read/Write from doobie)
2. Propagates effective access types up the call chain (memoized with cycle detection)
3. Finds entry points (methods with no callers) and walks the call graph to discover all paths
   from entry point to integration, producing `LineageChain` values

### 3.6 Mermaid Visualization

`MermaidRenderer.render(result)` produces a mermaid flowchart:
- Classes as subgraphs containing their methods
- DB tables as cylinder-shaped nodes
- Call graph edges as solid arrows
- Read integrations as dashed arrows (`-.->|Read|`)
- Write integrations as thick arrows (`==>|Write|`)
- Color coding: green (Read), red (Write), orange (ReadWrite), blue (DB)

`MermaidRenderer.toViewUrl(mermaidCode)` generates a `mermaid.live/edit#base64:...` URL for viewing.

Run with:
```
sbt "examples / runMain domaindocs4s.architecture.lineage.example.RenderLineage"
```

### 3.7 Example Output

Given three example classes using real doobie (`UserGrpcApi → UserService → UserRepo`):

```
=== Data Lineage Chains ===

  doobie: Write users
    UserGrpcApi.deposit -> UserService.deposit -> UserRepo.updateBalance
    evidence: UPDATE users SET balance =  WHERE id =

  doobie: Write transactions
    UserGrpcApi.deposit -> UserService.deposit -> UserRepo.insertTransaction
    evidence: INSERT INTO transactions (user_id, amount, description) VALUES (, , )

  doobie: Read users
    UserGrpcApi.getBalance -> UserService.getBalance -> UserRepo.getBalance
    evidence: SELECT balance FROM users WHERE id =

  doobie: Read transactions
    UserGrpcApi.getHistory -> UserService.getHistory -> UserRepo.getTransactions
    evidence: SELECT id, user_id, amount, description FROM transactions WHERE user_id =
```

### 3.8 Learnings and Limitations

**What worked well:**
- tasty-query gives full access to method bodies, types, and symbol resolution
- Real doobie types produce structurally predictable TASTy patterns
- The multi-phase split keeps scanner implementations focused and composable
- `TastyContext.fromCurrentProcess()` makes it trivial to scan from tests or `runMain`

**Current limitations:**
- SQL extraction from `sql"..."` interpolation loses parameter values (we get `WHERE id =` not `WHERE id = ?`).
  This is sufficient for table name extraction but not for full SQL analysis.
- Call graph extraction only resolves `val` field types in the immediate class. Constructor parameters that aren't
  `val` fields or locally created instances aren't tracked.
- The scanner matches specific AST shapes. Different doobie usage patterns (e.g., `HC.stream`, `Fragment.const`,
  custom query helpers) would need additional patterns.
- Package scanning is flat (one level). Nested packages or cross-package references need recursive scanning.

---

## 4. Concepts

### 4.1 Service Flow

> Design only — not yet implemented.

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

### 4.2 Views

Users define **views** — named filters on the full flow that focus on a particular area or aspect. Views make large
flows digestible without losing the single-source-of-truth property.

### 4.3 External Spec

Derived from the service flow. Nodes not marked as `internal` constitute the service's **external spec** — what it
produces for and consumes from the outside world. This is serialized to JSON and uploaded to a central repository.

### 4.4 System View

A central aggregation tool reads external specs from all services, matches produced and consumed artifacts, and renders
system-level dependency graphs.

---

## 5. Core Model

> Design only — not yet implemented. The lineage prototype (section 3) uses its own model.
> These two may converge as the design matures.

### 5.1 Node Categories and Artifacts

Nodes are typed using a two-layer system:

1. **`NodeCategory`** — a small, fixed enum of fundamental architectural categories. Drives visual encoding (node shape
   in diagrams) and coarse filtering.
2. **`Artifact`** — an open trait that carries category + instance-specific identifying information. The library ships
   common implementations, but users freely define their own.

```scala
enum NodeCategory {
  case Api       // something that exposes an interface (gRPC, HTTP, GraphQL, ...)
  case Message   // asynchronous messaging (Kafka topic, RabbitMQ queue, SQS, ...)
  case Dataset   // persistent data (DB table, S3 object, data warehouse, spreadsheet, ...)
  case Compute   // processing logic (component, projection, job, cache, ...)
}

trait Artifact {
  def category: NodeCategory
  def matchKey: String  // unique identifier, e.g. "kafka:ledger.movements"
}
```

Library-provided artifacts: `GrpcEndpoint`, `HttpEndpoint`, `KafkaTopic`, `DatabaseTable`, `DatabaseView`,
`S3Location`, `Spreadsheet`, `DataWarehouse`, `Journal`, `Component`, `Projection`, `Job`, `Cache`.

### 5.2 Node, Edge, FlowChart

```scala
case class Node(label: String, artifact: Artifact, internal: Boolean = true)
enum EdgeType { case Produces, Consumes }
case class Edge(from: Node, edgeType: EdgeType, to: Node, label: String = "")
case class Subgraph(label: String, nodes: List[Node])
case class FlowChart(edges: List[Edge], subgraphs: List[Subgraph] = Nil)
```

---

## 6. DSL

> Design only — not yet implemented.

```scala
// Node construction
def component[T](using ct: ClassTag[T]): Node
def kafkaTopic(topicName: String, label: String = ""): Node
def dbTable(database: String, schema: String, tableName: String, label: String = ""): Node
// ... etc.

// Edge construction
extension (n: Node) {
  infix def produces(to: Node): Edge
  infix def consumes(to: Node): Edge
}

// Usage
ledgerActor produces journal,
dailyBalanceChange consumes journal,
movementsProjection produces movementsTable,
```

---

## 7. Scanners

### 7.1 Design Principles

Scanning has **two distinct faces**:

- **Consumed artifacts**: Scanning is authoritative. If the code calls a gRPC client, that's a real dependency.
- **Produced artifacts**: Scanning discovers what exists, but the user curates what to expose.

There are **two kinds of scanners**:

- **Code scanners** — TASTy-based, for patterns in compiled Scala code (gRPC clients, Kafka producers, doobie queries)
- **Resource scanners** — file-based, for things TASTy can't see (SQL migrations, HOCON config)

### 7.2 TASTy-Based Scanning — Learnings from Prototype

The doobie scanner prototype (section 3.3) validated the TASTy scanning approach and revealed practical details:

**Pattern matching on TASTy trees works, but requires precision.** Each library produces specific AST shapes.
Real doobie's `.query[T]` has an extra `Apply` node for the implicit `Read[T]` parameter that a naive implementation
wouldn't have. Developing a scanner requires: (1) write example code with real library types, (2) dump the TASTy
tree to see actual patterns, (3) match those patterns precisely.

**`TypeOrMethodic` traversal for return type checking.** Method return types may be wrapped in `MethodType` or
`PolyType`. The scanner must unwrap recursively to find the actual return type (e.g., `ConnectionIO[_]`).

**Type names may differ from what you expect.** tasty-query's `TypeRef`, `AppliedType` etc. are `final class`, not
case classes. Pattern matching requires `.isInstanceOf` + field access, not constructor patterns.

**String interpolation produces `StringContext` trees.** `sql"SELECT ... FROM users WHERE id = $userId"` doesn't
produce a single string literal. Instead, the TASTy tree contains `StringContext.apply("SELECT ... FROM users WHERE id = ", "")`
with the string parts as a `SeqLiteral` inside a `Typed` node. SQL extraction must collect these parts.

**`TreeTraverser` handles desugared for-comprehensions.** For-comprehensions desugar to `flatMap`/`map` with
lambda bodies. The `TreeTraverser` from tasty-query automatically recurses into these, so the call graph extractor
finds method calls inside for-comprehension bodies without special handling.

### 7.3 Code Scanner Trait

> Design only — not yet implemented as a generic trait. The doobie scanner is a standalone class.

```scala
trait CodeScanner {
  def name: String
  def inspect(symbol: Symbol): List[DiscoveredArtifact]
}
```

Planned built-in scanners:

| Scanner                | Detects                      | Status      |
|------------------------|------------------------------|-------------|
| `DoobieScanner`        | Doobie SQL table references   | Implemented |
| `Fs2GrpcClientScanner` | gRPC client usages            | Planned     |
| `Fs2GrpcServerScanner` | gRPC service implementations  | Planned     |
| `Fs2KafkaScanner`      | Kafka topic configurations    | Planned     |
| `SlickTableScanner`    | Slick table definitions       | Planned     |
| `SttpClientScanner`    | HTTP client calls             | Planned     |

### 7.4 Resource Scanners

> Design only.

```scala
trait ResourceScanner {
  def name: String
  def filePattern: String
  def inspect(path: Path, content: String): List[DiscoveredArtifact]
}
```

Planned: `FlywayMigrationScanner`, `HoconKafkaTopicScanner`.

---

## 8. External Spec (JSON)

> Design only — not yet implemented.

Each service derives an external spec from its flow chart — the externally visible nodes serialized to JSON.
Services upload these specs to a central repository; a system-level aggregator matches produced and consumed
artifacts across services.

```json
{
  "service": { "id": "financial-ledger-service", "name": "Financial Ledger Service" },
  "produced": [
    { "type": "kafka-topic", "topicName": "ledger.movements" },
    { "type": "grpc-endpoint", "serviceName": "LedgerServiceAPI", "methodName": "GetUserBalance" }
  ],
  "consumed": [
    { "type": "grpc-endpoint", "serviceName": "RateServiceAPI", "methodName": "GetRate" }
  ]
}
```

---

## 9. System-Level Aggregation

> Design only — not yet implemented.

A separate tool reads all service JSON specs, matches artifacts by `matchKey`, and renders system-level dependency
graphs where each service is a subgraph and shared artifacts (Kafka topics, DB tables, gRPC endpoints) appear as
nodes between services.

---

## 10. Rendering

### 10.1 Lineage Mermaid Renderer (Implemented)

`MermaidRenderer` in the lineage package converts `ScanResult` to a mermaid flowchart:
- Classes as subgraphs, methods as nodes
- DB tables as cylinder-shaped nodes labeled with integration type
- Color-coded by access type (green=Read, red=Write, orange=ReadWrite, blue=DB)
- Read edges dashed, write edges thick
- `toViewUrl` generates a `mermaid.live/edit#base64:...` URL (same approach as workflows4s)

### 10.2 Flow Chart Mermaid Renderer (Design Only)

For the service flow model (section 5), a separate renderer with:
- Node shapes by category: Dataset → `[(cylinder)]`, Message → `{{hexagon}}`, Api → `{diamond}`, Compute → `[rectangle]`
- Edge styling: `Produces` → solid arrow, `Consumes` → dashed arrow

### 10.3 Cytoscape.js Renderer (Design Only)

A rich interactive viewer producing standalone HTML with:
- Pan/zoom, minimap, fit-to-screen
- Click-to-inspect detail panel
- Text search, view selector
- Export to PNG/SVG
- dagre layout (directed acyclic graph)

---

## 11. Integration with Existing domainDocs4s

### 11.1 Current Package Structure

```
domainDocs4s-examples/src/main/scala/domaindocs4s/architecture/
├── lineage/
│   ├── model.scala                    ← Core types + TastyUtils
│   ├── TastyDoobieScanner.scala       ← Doobie scanner + SqlUtils
│   ├── TastyCallGraphExtractor.scala  ← Generic call graph extractor
│   ├── LineageBuilder.scala           ← Generic lineage builder
│   ├── MermaidRenderer.scala          ← Mermaid output + URL generation
│   └── example/
│       ├── ExampleClasses.scala       ← UserGrpcApi → UserService → UserRepo (real doobie)
│       └── RenderLineage.scala        ← Main entry point for rendering
```

### 11.2 TASTy Scanning Reuse

The lineage scanners reuse `TastyContext.fromCurrentProcess()` from the core module. Both `Test / fork` and
`run / fork` must be `true` in sbt for the TASTy context to find compiled classes on the classpath.

### 11.3 Dependencies

The examples module adds `"org.tpolecat" %% "doobie-core"` for real doobie types in the example classes.
The scanner itself only depends on `tasty-query` — it pattern-matches TASTy tree shapes without importing doobie.

---

## 12. Open Questions

1. **Scanner generalization**: The doobie scanner matches specific AST shapes. Should we build a mini-DSL for
   declaring "match this call chain pattern" to make it easier to add new scanners? Or is hand-written pattern
   matching sufficient?

2. **Cross-package scanning**: The current prototype scans a single package. Real services have classes spread
   across multiple packages. Need recursive package scanning or explicit package lists.

3. **Lineage ↔ Service Flow convergence**: The lineage model (`DiscoveredIntegration`, `ScanResult`) and the
   service flow model (`Artifact`, `FlowChart`) are separate. They should converge — lineage scanner output
   could feed into the service flow model, with `DiscoveredIntegration` mapping to `Artifact` instances.

4. **Additional doobie patterns**: The scanner handles `sql"...".query[T].{unique,option,to[F]}` and
   `sql"...".update.run`. Real codebases also use `HC.stream`, `Fragment.const`, helper methods that wrap
   doobie, and custom query builders. How much coverage is enough?

5. **Proto package extraction**: The fs2-grpc scanner sees Java package names, not proto package names.
   For MVP, use service/method names from generated code.

---

## 13. Example

See `domainDocs4s-examples/src/main/scala/domaindocs4s/architecture/` for the working prototype.

Run the full pipeline:
```
sbt "examples / runMain domaindocs4s.architecture.lineage.example.RenderLineage"
```

Run tests:
```
sbt "examples / Test / testOnly domaindocs4s.lineage.TastyLineageScannerTest"
```
