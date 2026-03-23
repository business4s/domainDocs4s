package domaindocs4s.architecture

// ============================================================================
// Financial Ledger Service — Architecture Flow Declaration
//
// Simplified but representative model extracted from a real financial ledger
// service that provides double-entry bookkeeping with event sourcing, CQRS
// projections, Kafka publishing, and S3 exports.
// ============================================================================

// This object would be defined in the ledger service's docs/tooling module.
// In the real service, component() would reference actual classes:
//   val grpcApi = component[LedgerServiceAPIImpl]
//   val sagaActors = component[SagaEntitiesAlgebra[?]]("Saga Actors")

object LedgerServiceFlow {

  // ── Core service components ───────────────────────────────────────────────

  val grpcApi     = component("LedgerServiceAPI", "Ledger Service gRPC API")
  val sagaActors  = component("SagaEntitiesAlgebra", "Saga Actors")
  val ledgerActor = component("LedgerActor", "Ledger Actor")

  // ── Event store ───────────────────────────────────────────────────────────

  val pekkoJournal = journal("pekko")

  // ── Internal projections (read models within the service) ─────────────────

  val dailyBalanceChange = projection("DailyBalanceChange", "Daily Balance Change Projection")
  val legalEntityChange  = projection("LegalEntityChange", "Legal Entity Change Projection")
  val txIdTracking       = projection("EventTagProjection", "Transaction ID Tracking")

  // ── Caches ────────────────────────────────────────────────────────────────

  val balanceHistoryCache = cache("BalanceHistoryCache", "Balance History Cache")

  // ── Internal database tables ──────────────────────────────────────────────

  val dailyBalanceChangeTable = dbTable("ledger_service", "internal", "daily_balance_change")
  val legalEntityChangeTable  = dbTable("ledger_service", "internal", "legal_entity_change")

  // ── External projections (separate deployment, write to operational DB) ───

  val movementsProjection         = projection("MovementProjection", "Movement Projection")
  val accountBalancesProjection   = projection("AccountBalanceProjection", "Account Balance Projection")
  val operatorStatementProjection = projection("OperatorStatementProjection", "Operator Statement Projection")
  val dueToUsersCheckProjection   = projection("DueToUsersCheck", "Due-to-Users Check Projection")
  val exchangeRatesProjection     = projection("ExchangeRates", "Exchange Rates Projection")

  // ── Operational database tables (projections DB) ──────────────────────────
  // These are exposed because other services read from them.

  val movementsTable       = dbTable("operational_projections", "ledger", "movements").exposed
  val accountBalancesTable = dbTable("operational_projections", "ledger", "account_balances").exposed
  val operatorClosingTable = dbTable("operational_projections", "ledger", "operator_closing_of_account").exposed
  val dueToUsersCheckTable = dbTable("operational_projections", "ledger", "due_to_users_check")
  val exchangeRatesTable   = dbTable("operational_projections", "ledger", "current_exchange_rates")

  // ── Kafka topics ──────────────────────────────────────────────────────────

  val movementsTopic   = kafkaTopic("ledger.movements", "Movement Events Topic").exposed
  val legalEntityTopic = kafkaTopic("ledger.legal-entity-changes", "Legal Entity Events Topic").exposed

  // ── Jobs ──────────────────────────────────────────────────────────────────

  val userAssetsJob     = job("dumpUserAssets", "User Assets Export Job")
  val operatorAssetsJob = job("dumpOperatorAssets", "Operator Assets Export Job")

  // ── S3 (downstream) ──────────────────────────────────────────────────────

  val assetsS3 = s3Location("ledger-exports", prefix = "assets/", label = "Assets S3 Export").exposed

  // ── External dependencies (consumed from other services) ──────────────────

  val rateServiceGetRate        = grpcEndpoint("RateServiceAPI", "GetRate").exposed
  val configServiceGetInventory = grpcEndpoint("ConfigServiceAPI", "GetCurrencyInventory").exposed

  // ── Downstream consumers ──────────────────────────────────────────────────

  val redshift = dataWarehouse("Redshift", "ledger_movements").exposed

  // ── Flow declaration ──────────────────────────────────────────────────────

  val flow = FlowChart(
    edges = List(
      // Core request flow
      grpcApi produces sagaActors,
      grpcApi produces ledgerActor,
      sagaActors produces ledgerActor,
      ledgerActor produces pekkoJournal,

      // Internal projections consuming from journal
      dailyBalanceChange consumes pekkoJournal,
      legalEntityChange consumes pekkoJournal,
      txIdTracking consumes pekkoJournal,

      // Internal projections writing to internal DB
      dailyBalanceChange produces dailyBalanceChangeTable,
      legalEntityChange produces legalEntityChangeTable,

      // Cache serving gRPC API
      balanceHistoryCache consumes dailyBalanceChangeTable,
      grpcApi consumes balanceHistoryCache,

      // External projections consuming from journal
      movementsProjection consumes pekkoJournal,
      accountBalancesProjection consumes pekkoJournal,
      operatorStatementProjection consumes pekkoJournal,

      // External projections writing to operational DB
      movementsProjection produces movementsTable,
      accountBalancesProjection produces accountBalancesTable,
      operatorStatementProjection produces operatorClosingTable,

      // External projections writing to Kafka
      movementsProjection produces movementsTopic,
      legalEntityChange produces legalEntityTopic,

      // Due-to-users check (cross-cutting: reads internal DB, writes operational DB)
      dueToUsersCheckProjection consumes dailyBalanceChangeTable,
      dueToUsersCheckProjection consumes legalEntityChangeTable,
      dueToUsersCheckProjection produces dueToUsersCheckTable,

      // Exchange rates enrichment
      exchangeRatesProjection consumes dueToUsersCheckTable,
      exchangeRatesProjection consumes rateServiceGetRate,
      exchangeRatesProjection produces exchangeRatesTable,

      // Jobs exporting to S3
      userAssetsJob consumes dailyBalanceChangeTable,
      operatorAssetsJob consumes dailyBalanceChangeTable,
      userAssetsJob produces assetsS3,
      operatorAssetsJob produces assetsS3,

      // Kafka downstream to data warehouse
      redshift consumes movementsTopic,

      // External service consumption
      grpcApi consumes rateServiceGetRate,
      grpcApi consumes configServiceGetInventory,
    ),
    subgraphs = List(
      subgraph("Service")(grpcApi, sagaActors, ledgerActor, dailyBalanceChange, legalEntityChange, txIdTracking, balanceHistoryCache),
      subgraph("Projections")(
        movementsProjection,
        accountBalancesProjection,
        operatorStatementProjection,
        dueToUsersCheckProjection,
        exchangeRatesProjection,
      ),
      subgraph("Jobs")(userAssetsJob, operatorAssetsJob),
      subgraph("Internal DB")(pekkoJournal, dailyBalanceChangeTable, legalEntityChangeTable),
      subgraph("Operational DB")(movementsTable, accountBalancesTable, operatorClosingTable, dueToUsersCheckTable, exchangeRatesTable),
      subgraph("Kafka")(movementsTopic, legalEntityTopic),
      subgraph("External Services")(rateServiceGetRate, configServiceGetInventory),
    ),
  )

  // ── Views ─────────────────────────────────────────────────────────────────

  val coreWritePath = flow
    .view("Core Write Path")
    .only(grpcApi, sagaActors, ledgerActor, pekkoJournal)
    .build

  val kafkaPipeline = flow
    .view("Kafka Pipeline")
    .only(pekkoJournal, movementsProjection, movementsTopic, legalEntityChange, legalEntityTopic, redshift)
    .build

  val externalInterface = flow
    .view("External Interface")
    .only(grpcApi, movementsTable, accountBalancesTable, operatorClosingTable, movementsTopic, legalEntityTopic, assetsS3)
    .build
}
