package domaindocs4s.architecture

// ============================================================================
// System-Level View — Cross-Service Architecture
//
// This would be generated automatically by the aggregation tool reading
// external specs (JSON) from all services. Shown here manually to illustrate
// the target output.
//
// Key design: shared artifacts (Kafka topics, DB tables, gRPC endpoints)
// appear as nodes between services, making the integration points visible.
//
// Services in scope:
//   - Financial Ledger Service
//   - Controlling Service
//   - Rate Service (external, spec provided)
//   - Config Service (external, spec provided)
//   - Data Warehouse / Redshift (downstream sink)
// ============================================================================

object SystemView {

  // ── Services as top-level nodes ───────────────────────────────────────────

  val ledgerService      = component("LedgerService", "Financial Ledger Service")
  val controllingService = component("ControllingService", "Controlling Service")
  val rateService        = component("RateService", "Rate Service")
  val configService      = component("ConfigService", "Config Service")
  val dwRedshift         = dataWarehouse("Redshift", "ledger_movements")
  val treasurySheets     = spreadsheet("Google Sheets", label = "Treasury Sheets")

  // ── Shared artifacts (appear as nodes between services) ───────────────────

  // These are the artifacts that matched across service specs:
  //   Ledger produces db:operational_projections.ledger.operator_closing_of_account
  //     ↔ Controlling consumes db:operational_projections.ledger.operator_closing_of_account
  //
  //   Ledger produces grpc:RateServiceAPI/GetRate (consumed)
  //     ↔ Controlling consumes grpc:RateServiceAPI/GetRate

  val operatorClosingTable = dbTable("operational_projections", "ledger", "operator_closing_of_account")
  val ledgerGetBalances    = grpcEndpoint("LedgerServicePrivateAPI", "GetBalances")
  val rateGetRate          = grpcEndpoint("RateServiceAPI", "GetRate")
  val configGetInventory   = grpcEndpoint("ConfigServiceAPI", "GetCurrencyInventory")
  val movementsTopic       = kafkaTopic("ledger.movements", "Movement Events Topic")

  // ── System flow ───────────────────────────────────────────────────────────

  val systemFlow = FlowChart(
    edges = List(
      // Ledger → shared artifacts (produces)
      ledgerService produces operatorClosingTable,
      ledgerService produces movementsTopic,

      // Controlling → shared artifacts (consumes)
      controllingService consumes operatorClosingTable,
      controllingService consumes ledgerGetBalances,
      controllingService consumes rateGetRate,
      controllingService consumes configGetInventory,
      controllingService consumes treasurySheets,

      // Ledger consumes shared services
      ledgerService consumes rateGetRate,
      ledgerService consumes configGetInventory,

      // Services produce their APIs
      ledgerService produces ledgerGetBalances,
      rateService produces rateGetRate,
      configService produces configGetInventory,

      // Data warehouse consumes Kafka
      dwRedshift consumes movementsTopic,
    ),
    subgraphs = List(
      subgraph("Core Services")(ledgerService, controllingService),
      subgraph("Shared Services")(rateService, configService),
      subgraph("Shared Artifacts")(operatorClosingTable, ledgerGetBalances, rateGetRate, configGetInventory, movementsTopic),
      subgraph("External Data")(treasurySheets, dwRedshift),
    ),
  )

  // ── What the aggregation tool produces ────────────────────────────────────
  //
  // 1. The system flow chart above (rendered as Mermaid or Cytoscape.js)
  //
  // 2. A dependency matrix:
  //
  //   | Consumer ↓ / Producer → | Ledger | Rate | Config | Sheets |
  //   |-------------------------|--------|------|--------|--------|
  //   | Ledger                  | —      | gRPC | gRPC   |        |
  //   | Controlling             | gRPC+DB| gRPC | gRPC   | HTTP   |
  //   | Redshift                | Kafka  |      |        |        |
  //
  // 3. Anomaly report:
  //   - kafka:ledger.legal-entity-changes: produced by Ledger, no known consumer
  //   - s3:ledger-exports/assets/: produced by Ledger, no known consumer in tracked services

}
