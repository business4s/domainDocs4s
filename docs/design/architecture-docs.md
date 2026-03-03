# Architecture Documentation System — Design Document

## 1. Overview

This document describes the design for architecture documentation capabilities in domainDocs4s. The system aims to:

1. **Automatic data lineage** — scan compiled code to discover database access patterns and gRPC integrations,
   then trace them from API entry points through service layers to the actual queries/endpoints. Working prototype
   with doobie and fs2-grpc scanners.
2. **Service-level documentation** — each service defines its internal architecture, referencing real code symbols to
   keep docs in sync.
3. **External specification** — each service exports a JSON spec describing what it produces and consumes.
4. **System-level documentation** — a central tool aggregates external specs from all services and renders cross-service
   dependency diagrams.

The system produces interactive, embeddable documentation using Mermaid for simple output and Cytoscape.js for rich,
interactive views.

---

## 2. Status

| Component                        | Status      | Location                                          |
|----------------------------------|-------------|---------------------------------------------------|
| Data lineage model               | Implemented | `core: lineage/model.scala`                       |
| Doobie scanner (TASTy-based)     | Implemented | `core: lineage/TastyDoobieScanner.scala`          |
| fs2-grpc scanner (server+client) | Implemented | `core: lineage/TastyFs2GrpcScanner.scala`         |
| Manual scanner (Kafka + custom)  | Implemented | `core: lineage/ManualScanner.scala`               |
| Method ref macro (`_.method`)    | Implemented | `core: macros/MethodRefMacro.scala`               |
| Call graph extractor             | Implemented | `core: lineage/TastyCallGraphExtractor.scala`     |
| Lineage builder                  | Implemented | `core: lineage/LineageBuilder.scala`              |
| Mermaid visualization            | Implemented | `core: lineage/MermaidRenderer.scala`             |
| Example classes (doobie+grpc+kafka)| Implemented | `examples: lineage/example/ExampleClasses.scala`|
| Proto files (user+rate service)  | Implemented | `examples: src/main/protobuf/{user,rate}_service.proto` |
| Integration grouping             | Implemented | `core: lineage/model.scala` (IntegrationGroupConfig) |
| Class-level Mermaid rendering    | Implemented | `core: lineage/MermaidRenderer.scala`             |
| Class-level config (fold+hide)   | Implemented | `core: lineage/model.scala` (ClassLevelConfig)    |
| Tests (34 tests passing)         | Implemented | `examples: TastyLineageScannerTest.scala`         |
| Service flow DSL                 | Design only | Section 6 below                                   |
| External spec / system view      | Design only | Sections 8-9 below                                |
| Cytoscape.js renderer            | Design only | Section 10.3 below                                |
| Other TASTy scanners (Slick etc.)| Design only | Section 7 below                                   |
| Backstage integration            | Research    | Section 13 below                                  |

All lineage infrastructure is in `domainDocs4s-core/src/main/scala/domaindocs4s/architecture/lineage/`.
Example classes and tests are in `domainDocs4s-examples/`.

---

## 3. Data Lineage — Prototype

### 3.1 Architecture

The lineage system has three independent phases, each with its own output type. This lets us add new integration
scanners (kafka, gRPC, etc.) without changing the lineage builder.

```
Phase 0: Call Graph Extraction          Phase 1: Integration Scanning
┌──────────────────────────┐            ┌───────────────────────────┐
│  TastyCallGraphExtractor │            │  TASTy-based (automatic): │
│                          │            │    TastyDoobieScanner     │
│  TASTy ──→ List[         │            │    TastyFs2GrpcScanner    │
│    ExtractedMethod       │            │                           │
│  ]                       │            │  Manual declaration:      │
│                          │            │    ManualScanner          │
│  Generic: any package    │            │    (Kafka, custom, ...)   │
│  field.method() calls    │            │                           │
└────────────┬─────────────┘            │  All ──→ List[            │
             │                          │    DiscoveredIntegration   │
             │                          │  ]                        │
             │                          └─────────────┬─────────────┘
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
                  │  - Entry point → ext     │
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
    group: Option[String] = None,   // logical group: service name, database, ...
)

// Enrichment — assign groups to integrations by source class
case class IntegrationGroupConfig(classToGroup: Map[String, String]) {
  def enrich(integrations: List[DiscoveredIntegration]): List[DiscoveredIntegration]
}
// Type-safe builder: IntegrationGroupConfig.builder.group[UserRepo]("user-db").build

// Configuration for class-level Mermaid rendering
case class ClassLevelConfig(
    foldByGroup: Set[String] = Set("grpc"),  // integration types to collapse by group
    hiddenClasses: Set[String] = Set.empty,   // classes to hide (integrations promoted to callers)
)
// Type-safe builder: ClassLevelConfig.builder.hide[UserRepo].build

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
    integration: DiscoveredIntegration, // the external operation at the end
)
```

Key design decisions:
- `DiscoveredIntegration` is the contract any scanner must produce. Adding a Kafka scanner means
  producing `DiscoveredIntegration(integrationType = "kafka", target = "topic-name", ...)`.
- `LineageBuilder` is fully generic — it doesn't know about doobie or gRPC, only about call graphs and integrations.
- Access types propagate recursively: if `Service.deposit` calls `Repo.updateBalance` (Write) and
  `Repo.insertTransaction` (Write), then `Service.deposit` is Write. If it called both reads and writes,
  it would be ReadWrite.
- `group` enables visual grouping in diagrams: gRPC targets are auto-grouped by service name (set by the
  scanner), while DB tables are grouped via user-supplied `IntegrationGroupConfig` enrichment (e.g.,
  all integrations from `UserRepo` belong to `"user-db"`).

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

### 3.4 fs2-grpc Scanner

`TastyFs2GrpcScanner` scans compiled Scala code via tasty-query to detect gRPC server exposure and client
consumption. It uses real `sbt-fs2-grpc` code generation from `.proto` files.

The scanner detects two patterns:

**Server detection (Write):** A class that extends a `*Fs2Grpc` trait is a gRPC server implementation.
For each RPC method defined in the parent trait that the class implements, the scanner emits a Write
integration with target `ServiceName/methodName` and `group = Some(serviceName)`.

Detection approach:
1. Inspect `cls.parents` (type-level, not `parentClasses` — see learnings below)
2. Filter for parent types whose name ends with `Fs2Grpc`
3. Resolve the parent trait's `ClassSymbol` via `TypeRef.optSymbol`
4. Intersect parent trait declarations with the class's own methods
5. Service name is derived by stripping the `Fs2Grpc` suffix

**Client detection (Read):** A `val` field whose type name ends with `*Fs2Grpc` is a gRPC client.
When a method body calls `field.rpcMethod(...)`, the scanner emits a Read integration with target
`ServiceName/methodName` and `group = Some(serviceName)`.

Detection approach:
1. Resolve field types (like the call graph extractor), handling both `TypeRef` and `AppliedType`
2. Filter fields whose type name ends with `Fs2Grpc`
3. Walk method bodies with `TreeTraverser` looking for `Apply(Select(Ident(field), method), _)` patterns
4. Service name derived from the field's type name minus the `Fs2Grpc` suffix

### 3.5 Manual Scanner (Kafka and Custom Integrations)

`ManualScanner` handles integrations that can't be auto-detected from TASTy — Kafka producers/consumers
(library-specific: fs2-kafka, pekko-kafka, etc.), custom protocols, or any integration where the usage
pattern varies too much for reliable AST matching.

The builder uses a compile-time macro (`MethodRefMacro` in `domainDocs4s-core`) to extract class and
method names from `_.methodName` lambdas, providing type-safe references instead of raw strings:

```scala
val manualIntegrations = ManualScanner.builder
  .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
  .method[EventConsumer](_.consume).reads.kafka("input.events", cluster = "Analytics")
  .method[S3Exporter](_.export).writes.custom("s3", "my-bucket/exports", group = Some("S3"))
  .build
```

Key design decisions:
- **Same output type**: Produces `List[DiscoveredIntegration]`, identical to automatic scanners.
  Composable via simple concatenation before passing to `LineageBuilder`.
- **Kafka cluster grouping**: `.kafka(topic, cluster)` sets `group = Some(cluster)`, defaulting to
  `"Kafka"`. Topics render grouped by cluster in diagrams.
- **Generic escape hatch**: `.custom(integrationType, target)` supports any integration type not
  covered by specific methods. The `kafka()` method itself delegates to `custom()`.
- **Macro in core module**: `MethodRefMacro` lives in `domainDocs4s-core` (compiles before examples)
  and extracts `(className, methodName)` from `T => Any` lambdas at compile time. Zero runtime overhead.

The manual scanner integrates with the call graph: if `UserGrpcApi.deposit` calls
`EventPublisher.publishDeposit`, and ManualScanner declares that `publishDeposit` writes to Kafka,
the lineage builder produces the chain: `UserGrpcApi.deposit → EventPublisher.publishDeposit → kafka:user.deposit-events`.

### 3.6 Call Graph Extractor

`TastyCallGraphExtractor` is generic (not scanner-specific). It:

1. Finds all user-defined classes in a package (filters out synthetic `$` companions and `<init>`)
2. Resolves field types: `class UserService(val repo: UserRepo)` → `repo: UserRepo`
3. Walks method bodies looking for `field.method(...)` patterns in the TASTy tree
4. Maps `repo.getBalance(...)` → `MethodRef("UserRepo", "getBalance")` using the resolved field types

This uses `TreeTraverser` from tasty-query which recursively visits all sub-trees, including lambda
bodies from for-comprehension desugaring (`flatMap`, `map`).

Note: The call graph extractor currently only resolves simple `TypeRef` field types. Fields with
`AppliedType` (e.g., `RateServiceFs2Grpc[IO, Metadata]`) are not resolved — those external calls
are detected by the gRPC scanner instead, keeping concerns separated.

### 3.7 Lineage Builder

`LineageBuilder.build(callGraph, integrations)` combines both phases:

1. Assigns direct access types from scanner output (Repo methods get Read/Write from doobie,
   gRPC API methods get Write from server exposure and Read from client calls)
2. Propagates effective access types up the call chain (memoized with cycle detection)
3. Finds entry points (methods with no callers) and walks the call graph to discover all paths
   from entry point to integration, producing `LineageChain` values

### 3.8 Mermaid Visualization

`MermaidRenderer` converts `ScanResult` to a mermaid flowchart. There are two levels of detail, each
with two arrow modes:

**Method-level** (detailed — each class is a subgraph with method nodes):
- **`render(result)`** — arrows follow access direction (method → target)
- **`renderDataFlow(result)`** — arrows follow data flow (target → method for reads)

**Class-level** (architecture overview — each class is a single node):
- **`renderClassLevel(result, config)`** — arrows follow access direction
- **`renderClassLevelDataFlow(result, config)`** — arrows follow data flow

Class-level rendering is controlled by `ClassLevelConfig`:
- **`foldByGroup`** (default `Set("grpc")`): integration types in this set collapse all targets
  within a group into a single node. E.g., `UserService/getBalance`, `UserService/deposit`,
  `UserService/getHistory` → one `UserService` hexagon node.
- **`hiddenClasses`**: classes to exclude from the diagram. Their integrations are promoted to the
  nearest non-hidden caller class. E.g., hiding `UserRepo` makes `UserService` connect directly to
  the DB tables `users` and `transactions`. Call edges through hidden classes are resolved
  transitively (A → B(hidden) → C becomes A → C).

Both use a type-safe builder with `ClassTag`:
```scala
val config = ClassLevelConfig.builder
  .hide[UserRepo]
  .foldByGroup(Set("grpc", "kafka"))
  .build
MermaidRenderer.renderClassLevel(result, config)
```

Visual encoding (shared across all modes):
- DB tables as cylinder-shaped nodes (blue)
- gRPC endpoints as hexagon-shaped nodes (purple)
- Kafka topics as stadium-shaped nodes (green)
- Integration targets grouped by `group` field into subgraphs (ungrouped targets render standalone)
  - When a group name collides with a class name, `(ext)` is appended to the label
- Call graph edges as solid arrows
- Read integrations as dashed arrows (`-.->|Read|`)
- Write integrations as thick arrows (`==>|Write|`)
- ReadWrite integrations as normal arrows (`-->|ReadWrite|`)
- Node color coding: green (Read), red (Write), orange (ReadWrite)

`MermaidRenderer.toViewUrl(mermaidCode)` generates a `mermaid.live/edit#base64:...` URL for viewing.

Run with:
```
sbt "examples / runMain domaindocs4s.architecture.lineage.example.RenderLineage"
```

### 3.9 Example Output

Given four example classes using real doobie, fs2-grpc, and manual Kafka declarations
(`UserGrpcApi → UserService → UserRepo`, `UserGrpcApi → RateServiceFs2Grpc`,
`UserGrpcApi → EventPublisher → Kafka`).

**Method-level** lineage chains (text output):

```
=== Data Lineage Chains ===

  grpc: Write UserService/deposit
    UserGrpcApi.deposit
    evidence: implements UserServiceFs2Grpc

  grpc: Read RateService/getRate
    UserGrpcApi.deposit
    evidence: calls rateClient.getRate

  doobie: Write users
    UserGrpcApi.deposit -> UserService.deposit -> UserRepo.updateBalance
    evidence: UPDATE users SET balance =  WHERE id =

  doobie: Write transactions
    UserGrpcApi.deposit -> UserService.deposit -> UserRepo.insertTransaction
    evidence: INSERT INTO transactions (user_id, amount, description) VALUES (, , )

  kafka: Write user.deposit-events
    UserGrpcApi.deposit -> EventPublisher.publishDeposit
    evidence: manual declaration

  grpc: Write UserService/getBalance
    UserGrpcApi.getBalance
    evidence: implements UserServiceFs2Grpc

  doobie: Read users
    UserGrpcApi.getBalance -> UserService.getBalance -> UserRepo.getBalance
    evidence: SELECT balance FROM users WHERE id =

  grpc: Write UserService/getHistory
    UserGrpcApi.getHistory
    evidence: implements UserServiceFs2Grpc

  doobie: Read transactions
    UserGrpcApi.getHistory -> UserService.getHistory -> UserRepo.getTransactions
    evidence: SELECT id, user_id, amount, description FROM transactions WHERE user_id =
```

**Class-level** diagram with `hide[UserRepo]` (Mermaid nodes and edges):
```
Nodes: UserGrpcApi, UserService, EventPublisher  (UserRepo hidden)

Call edges:
  UserGrpcApi --> UserService
  UserGrpcApi --> EventPublisher

Integration edges (promoted from hidden UserRepo to UserService):
  UserService -->|ReadWrite| users
  UserService -->|ReadWrite| transactions

Folded gRPC (one node per service):
  UserGrpcApi ==>|Write| UserService (ext)  (hexagon)
  UserGrpcApi -.->|Read| RateService        (hexagon)

Kafka:
  EventPublisher ==>|Write| user.deposit-events  (stadium)
```

### 3.10 Learnings and Limitations

**What worked well:**
- tasty-query gives full access to method bodies, types, and symbol resolution
- Real doobie types produce structurally predictable TASTy patterns
- The multi-phase split keeps scanner implementations focused and composable
- `TastyContext.fromCurrentProcess()` makes it trivial to scan from tests or `runMain`
- Real proto files with `sbt-fs2-grpc` code generation produce clean, predictable TASTy patterns
- The `DiscoveredIntegration` contract works well across different scanner types — doobie and gRPC
  scanners produce the same output type and the lineage builder handles both generically

**tasty-query quirks:**
- `ClassSymbol.parentClasses` throws `MemberNotFoundException` when it can't resolve `java.lang.Object`
  in the classpath. Workaround: use `cls.parents` (returns `List[Type]`) and resolve via `TypeRef.optSymbol`.
- Field types for generic types like `Fs2Grpc[IO, Metadata]` are `AppliedType`, not `TypeRef`. The underlying
  `TypeRef` is accessible via `AppliedType.tycon`. Both must be handled when resolving type names.
- Generated code from `sbt-fs2-grpc` places traits in sub-packages (e.g., `grpc.user_service.UserServiceFs2Grpc`),
  not the proto package directly. This doesn't affect the scanner since it resolves parent symbols across packages.

**Current limitations:**
- SQL extraction from `sql"..."` interpolation loses parameter values (we get `WHERE id =` not `WHERE id = ?`).
  This is sufficient for table name extraction but not for full SQL analysis.
- Call graph extraction only resolves simple `TypeRef` field types. `AppliedType` fields (generic types) are
  not tracked in the call graph — they are handled by integration scanners instead.
- The scanners match specific AST shapes. Different library usage patterns (e.g., `HC.stream`, `Fragment.const`,
  streaming gRPC) would need additional patterns.
- Package scanning is flat (one level). Nested packages or cross-package references need recursive scanning.
- The gRPC scanner uses naming convention (`*Fs2Grpc` suffix) for detection. This is stable for `sbt-fs2-grpc`
  but wouldn't detect other gRPC libraries.

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

### 7.2 TASTy-Based Scanning — Learnings from Implementation

The doobie and fs2-grpc scanners validated the TASTy scanning approach and revealed practical details:

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

**Use `cls.parents` not `cls.parentClasses`.** The `parentClasses` method on `ClassSymbol` throws
`MemberNotFoundException` when it can't resolve `java.lang.Object` in the classpath (common when scanning
from a forked JVM). The workaround is to use `cls.parents` (returns `List[Type]`) and resolve the `ClassSymbol`
via `TypeRef.optSymbol`.

**`AppliedType` must be handled alongside `TypeRef`.** Generic types like `Fs2Grpc[IO, Metadata]` appear as
`AppliedType` in TASTy, not `TypeRef`. The underlying `TypeRef` is at `AppliedType.tycon`. Any code that
resolves type names must handle both cases.

**Code generation naming conventions are stable detection signals.** `sbt-fs2-grpc` consistently generates
traits named `*Fs2Grpc[F[_], A]` in sub-packages named after the proto service. This naming convention is a
reliable detection signal for the scanner.

### 7.3 Code Scanner Trait

> Design only — not yet implemented as a generic trait. The scanners are standalone classes.

```scala
trait CodeScanner {
  def name: String
  def inspect(symbol: Symbol): List[DiscoveredArtifact]
}
```

Implemented and planned scanners:

| Scanner                | Detects                      | Status      |
|------------------------|------------------------------|-------------|
| `DoobieScanner`        | Doobie SQL table references   | Implemented |
| `Fs2GrpcClientScanner` | gRPC client usages            | Implemented |
| `Fs2GrpcServerScanner` | gRPC service implementations  | Implemented |
| `ManualScanner`        | Kafka, custom integrations    | Implemented |
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

`MermaidRenderer` in the lineage package converts `ScanResult` to mermaid flowcharts at two levels:

**Method-level** (detailed view):
- `render(result)` — access direction (method → target)
- `renderDataFlow(result)` — data flow (reversed reads)
- Classes as subgraphs, each method a node

**Class-level** (architecture overview):
- `renderClassLevel(result, config)` — access direction
- `renderClassLevelDataFlow(result, config)` — data flow
- Each class is a single node (not a subgraph with methods)
- Configurable via `ClassLevelConfig`: fold integration groups (e.g., collapse all gRPC endpoints
  per service into one node), hide technical classes (e.g., repo layer) with integration promotion
  to callers

Visual encoding (both levels):
- DB tables as cylinder-shaped nodes (blue)
- gRPC endpoints as hexagon-shaped nodes (purple)
- Kafka topics as stadium-shaped nodes (green)
- Integration targets grouped into subgraphs by `group` field (ungrouped targets render standalone)
- Color-coded by access type (green=Read, red=Write, orange=ReadWrite)
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
domainDocs4s-core/
├── src/main/scala/domaindocs4s/
│   ├── macros/
│   │   └── MethodRefMacro.scala           ← Compile-time _.method name extraction
│   └── architecture/lineage/
│       ├── model.scala                    ← Core types + TastyUtils
│       ├── TastyDoobieScanner.scala       ← Doobie scanner + SqlUtils
│       ├── TastyFs2GrpcScanner.scala      ← fs2-grpc server + client scanner
│       ├── ManualScanner.scala            ← Manual integration declarations (Kafka, custom)
│       ├── TastyCallGraphExtractor.scala  ← Generic call graph extractor
│       ├── LineageBuilder.scala           ← Generic lineage builder
│       └── MermaidRenderer.scala          ← Mermaid output + URL generation

domainDocs4s-examples/
├── src/main/protobuf/
│   ├── user_service.proto             ← UserService gRPC definition
│   └── rate_service.proto             ← RateService gRPC definition
├── src/main/scala/domaindocs4s/architecture/
│   └── lineage/example/
│       ├── ExampleClasses.scala       ← UserGrpcApi → UserService → UserRepo → DB
│       │                                 UserGrpcApi → EventPublisher → Kafka
│       └── RenderLineage.scala        ← Main entry point for rendering
```

### 11.2 TASTy Scanning Reuse

The lineage scanners reuse `TastyContext.fromCurrentProcess()` from the core module. Both `Test / fork` and
`run / fork` must be `true` in sbt for the TASTy context to find compiled classes on the classpath.

### 11.3 Dependencies

The core module (`domainDocs4s-core`) depends on:
- `"ch.epfl.scala" %% "tasty-query"` — used by all TASTy-based scanners and `TastyUtils`

The TASTy scanners only depend on `tasty-query` — they pattern-match TASTy tree shapes by string name
without importing doobie or gRPC libraries. This is why all lineage infrastructure lives in core.
`ManualScanner` depends on `MethodRefMacro` (uses `scala.quoted.*` macros).

The examples module (`domainDocs4s-examples`) depends on core and adds:
- `"org.tpolecat" %% "doobie-core"` for real doobie types in the example classes
- `sbt-fs2-grpc` plugin for proto compilation and fs2-grpc code generation (brings in `fs2-grpc-runtime`,
  `scalapb-runtime`, `grpc-api` transitively)
- `-Wconf:src=target/scala-.*:s` to suppress warnings from generated protobuf/scalapb code

---

## 12. Open Questions

1. **Scanner generalization**: The doobie scanner matches specific AST shapes. Should we build a mini-DSL for
   declaring "match this call chain pattern" to make it easier to add new scanners? Or is hand-written pattern
   matching sufficient? (Experience with two scanners suggests hand-written is manageable, but they share
   significant structural patterns that could be extracted.)

2. **Cross-package scanning**: The current prototype scans a single package. Real services have classes spread
   across multiple packages. Need recursive package scanning or explicit package lists.

3. **Lineage ↔ Service Flow convergence**: The lineage model (`DiscoveredIntegration`, `ScanResult`) and the
   service flow model (`Artifact`, `FlowChart`) are separate. They should converge — lineage scanner output
   could feed into the service flow model, with `DiscoveredIntegration` mapping to `Artifact` instances.

4. **Additional doobie patterns**: The scanner handles `sql"...".query[T].{unique,option,to[F]}` and
   `sql"...".update.run`. Real codebases also use `HC.stream`, `Fragment.const`, helper methods that wrap
   doobie, and custom query builders. How much coverage is enough?

5. **Proto package extraction**: The fs2-grpc scanner derives service names from generated trait names (e.g.,
   `UserServiceFs2Grpc` → `UserService`). This works but loses the original proto package information. For
   cross-service matching, the proto package or fully-qualified service name may be needed.

---

## 13. Backstage Integration (Research)

> Not yet implemented. Options documented here for further research.

[Backstage](https://backstage.io/) is an open platform for building developer portals. Its **Software Catalog**
models services, APIs, and their relationships using YAML descriptor files. domainDocs4s lineage output maps
naturally onto Backstage's entity model.

### 13.1 Relevant Backstage Entities

| Backstage Kind | Maps from domainDocs4s | Key fields |
|----------------|------------------------|------------|
| `Component`    | Scanned service class (e.g., `UserGrpcApi`) | `spec.providesApis`, `spec.consumesApis` |
| `API`          | `DiscoveredIntegration` with `integrationType = "grpc"` | `spec.type: grpc`, `spec.definition` (proto) |
| `Resource`     | `DiscoveredIntegration` with `integrationType = "doobie"` | DB tables, Kafka topics |
| `System`       | Group of related components | `spec.owner`, lifecycle |

### 13.2 Mapping: Lineage → Backstage YAML

A `ScanResult` contains enough information to generate Backstage catalog descriptors:

**API entities** from gRPC integrations:
```yaml
apiVersion: backstage.io/v1alpha1
kind: API
metadata:
  name: user-service
  description: User balance and transaction management
spec:
  type: grpc
  lifecycle: production
  owner: team-payments
  definition: |
    # Could embed or reference the .proto file
    service UserService { ... }
```

**Component entities** with `providesApis` / `consumesApis` derived from scan:
```yaml
apiVersion: backstage.io/v1alpha1
kind: Component
metadata:
  name: user-grpc-api
spec:
  type: service
  lifecycle: production
  owner: team-payments
  providesApis:
    - user-service          # from Write integrations (server impl)
  consumesApis:
    - rate-service          # from Read integrations (client usage)
```

The key mapping rules:
- `DiscoveredIntegration(accessType=Write, integrationType="grpc")` → `spec.providesApis` entry
- `DiscoveredIntegration(accessType=Read, integrationType="grpc")` → `spec.consumesApis` entry
- Each unique gRPC service name → a `Kind: API` entity with `spec.type: grpc`
- DB tables could map to `Kind: Resource` entities

### 13.3 Integration Options

**Option A: Generate catalog-info.yaml as a renderer output**

Add a `BackstageCatalogRenderer` alongside `MermaidRenderer` that takes a `ScanResult` and produces
Backstage-compatible YAML. This would be run during CI or as an sbt task, outputting catalog files
that Backstage discovers via its entity provider.

Pros: Simple, stateless, fits existing architecture (just another renderer).
Cons: Requires additional metadata (owner, lifecycle, system) that the scanner doesn't produce — needs
a user-provided mapping or config file.

**Option B: Backstage entity provider plugin**

Build a custom Backstage backend plugin that calls domainDocs4s at catalog refresh time. The plugin
would run the scanner and translate results into Backstage entities dynamically.

Pros: Always up-to-date, no generated files to commit.
Cons: Requires a running Backstage instance, more complex to build, Backstage plugin in TypeScript
vs domainDocs4s in Scala.

**Option C: Enrich existing catalog-info.yaml**

Many teams already maintain `catalog-info.yaml` manually. domainDocs4s could read an existing file,
add/update `providesApis` and `consumesApis` based on scan results, and write it back — keeping
manually maintained fields (owner, lifecycle, description) untouched.

Pros: Works with existing Backstage setups, low risk, additive only.
Cons: Needs careful merge logic to avoid clobbering manual entries.

### 13.4 Open Questions for Further Research

1. **Granularity**: Should each gRPC RPC method be its own API entity (`user-service-get-balance`), or
   should the whole proto service be one API entity (`user-service`)? Backstage convention seems to
   favor service-level granularity with the proto definition containing method details.
2. **DB tables as Resources**: Backstage has `Kind: Resource` for infrastructure. DB tables could be
   modeled there with `spec.type: database`. Worth exploring for the doobie scanner output.
3. **Relation to External Spec (section 8)**: The External Spec JSON format and Backstage YAML serve
   similar purposes (declaring what a service produces/consumes). Could Backstage YAML replace the
   custom JSON format, or should both exist with a shared internal model?
4. **Proto file linking**: Backstage API entities support `spec.definition` for the API schema. For
   gRPC, this could embed or `$text: ./path/to/service.proto` reference the actual proto file.
5. **`catalog-info.yaml` location**: Backstage typically expects one `catalog-info.yaml` at the repo
   root. Multi-service repos need the multi-entity format (`---` separated documents in one file).

---

## 14. Example

See `domainDocs4s-examples/src/main/scala/domaindocs4s/architecture/` for the working prototype.

Run the full pipeline (outputs 4 mermaid.live URLs — method-level and class-level, each in access
direction and data flow modes):
```
sbt "examples / runMain domaindocs4s.architecture.lineage.example.RenderLineage"
```

Run tests (34 tests):
```
sbt "examples / Test / testOnly domaindocs4s.lineage.TastyLineageScannerTest"
```
