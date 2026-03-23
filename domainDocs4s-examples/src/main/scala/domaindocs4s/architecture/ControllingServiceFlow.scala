package domaindocs4s.architecture

// ============================================================================
// Controlling Service — Architecture Flow Declaration
//
// A financial reporting service that:
// - Reads operator balances from the ledger service (gRPC + direct DB)
// - Reads exchange rates from the rate service (gRPC)
// - Reads treasury operations from Google Sheets
// - Generates daily cash flow reports
// ============================================================================

object ControllingServiceFlow {

  // ── HTTP API ──────────────────────────────────────────────────────────────

  val httpApi = httpEndpoint("POST", "/reports/generate").exposed

  // ── Core logic ────────────────────────────────────────────────────────────

  val controllingService = component("ControllingService", "Controlling Service")
  val cashFlowMethod     = component("CashFlowMethodService", "Cash Flow Method Service")
  val ledgerDataService  = component("LedgerDataService", "Ledger Data Service")
  val rateServiceLocal   = component("RateServiceLocal", "Rate Service (local)")
  val treasuryDatasource = component("TreasuryDatasource", "Treasury Datasource")

  // ── Service's own database ────────────────────────────────────────────────

  val accountBalancesTable = dbTable("controlling_service", "app", "account_balances")

  // ── Operational reporting table ───────────────────────────────────────────

  val cashflowMethodTable = dbTable("operational_projections", "controlling", "cashflow_method").exposed

  // ── Consumed: Ledger Service gRPC (per-endpoint) ──────────────────────────

  val ledgerGetBalances = grpcEndpoint("LedgerServicePrivateAPI", "GetBalances").exposed

  // ── Consumed: Rate Service gRPC ───────────────────────────────────────────

  val rateGetRate = grpcEndpoint("RateServiceAPI", "GetRate").exposed

  // ── Consumed: Config Service gRPC ─────────────────────────────────────────

  val configGetInventory = grpcEndpoint("ConfigServiceAPI", "GetCurrencyInventory").exposed

  // ── Consumed: Ledger's projection database (cross-service DB read) ────────
  // Direct database read from another service's operational projections.
  // A legitimate integration pattern — not every dependency goes through an API.

  val ledgerOperatorClosingTable = dbTable(
    "operational_projections",
    "ledger",
    "operator_closing_of_account",
  ).exposed

  // ── Consumed: Google Sheets (treasury operations) ─────────────────────────

  val treasurySheet = spreadsheet("Google Sheets", label = "Treasury Operations Sheet").exposed

  // ── Flow declaration ──────────────────────────────────────────────────────

  val flow = FlowChart(
    edges = List(
      // HTTP trigger
      httpApi produces controllingService,

      // Core orchestration
      controllingService produces cashFlowMethod,
      controllingService produces accountBalancesTable,

      // Cash flow calculation dependencies
      cashFlowMethod consumes ledgerDataService,
      cashFlowMethod consumes rateServiceLocal,
      cashFlowMethod consumes treasuryDatasource,
      cashFlowMethod produces cashflowMethodTable,

      // Ledger data: dual source — gRPC for current state, DB for historical deltas
      ledgerDataService consumes ledgerGetBalances,
      ledgerDataService consumes ledgerOperatorClosingTable,

      // Rate data
      rateServiceLocal consumes rateGetRate,

      // Treasury data from spreadsheet
      treasuryDatasource consumes treasurySheet,

      // Config
      controllingService consumes configGetInventory,
    ),
    subgraphs = List(
      subgraph("Controlling Service")(httpApi, controllingService, cashFlowMethod, ledgerDataService, rateServiceLocal, treasuryDatasource),
      subgraph("Own Database")(accountBalancesTable),
      subgraph("Operational DB")(cashflowMethodTable),
      subgraph("External Services")(ledgerGetBalances, rateGetRate, configGetInventory),
      subgraph("External Data")(ledgerOperatorClosingTable, treasurySheet),
    ),
  )
}
