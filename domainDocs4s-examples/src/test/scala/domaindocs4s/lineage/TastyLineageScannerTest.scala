package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.example.{EventPublisher, UserGrpcApi, UserRepo, UserService}
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyLineageScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  // Phase 0: extract call graph
  private val callGraph = new TastyCallGraphExtractor().extract(pkg)

  // Phase 1: scan integrations
  private val doobieIntegrations = new TastyDoobieScanner().scan(List(pkg))
  private val grpcIntegrations = new TastyFs2GrpcScanner().scan(List(pkg))

  // Enrichment: assign groups
  private val enrichment = IntegrationGroupConfig.builder
    .group[UserRepo]("user-db")
    .build
  private val integrations = enrichment.enrich(doobieIntegrations ++ grpcIntegrations)

  // Phase 2: build lineage
  private val result = LineageBuilder.build(callGraph, integrations)

  "TastyDoobieScanner" - {

    "outputs DiscoveredIntegration with classA.methodB reads/writes tableC" in {
      doobieIntegrations should not be empty
      doobieIntegrations.foreach { di =>
        di.integrationType shouldBe "doobie"
        di.method.className should not be empty
        di.method.methodName should not be empty
        di.target should not be "unknown"
      }
    }

    "detects doobie integrations in UserRepo" in {
      doobieIntegrations.foreach(_.integrationType shouldBe "doobie")

      val tables = doobieIntegrations.map(_.target).toSet
      tables should contain("users")
      tables should contain("transactions")
    }

    "classifies reads and writes correctly" in {
      val reads = doobieIntegrations.filter(_.accessType == DataAccessType.Read)
      val writes = doobieIntegrations.filter(_.accessType == DataAccessType.Write)

      reads.map(_.method.methodName).toSet should contain allOf ("getBalance", "getTransactions")
      writes.map(_.method.methodName).toSet should contain allOf ("insertTransaction", "updateBalance")
    }

    "enriched doobie integrations have group user-db" in {
      val enrichedDoobie = integrations.filter(_.integrationType == "doobie")
      enrichedDoobie should not be empty
      enrichedDoobie.foreach(_.group shouldBe Some("user-db"))
    }
  }

  "TastyFs2GrpcScanner" - {

    "detects server implementations" in {
      val serverIntegrations = grpcIntegrations.filter(_.accessType == DataAccessType.Write)
      serverIntegrations should not be empty

      val targets = serverIntegrations.map(_.target).toSet
      targets should contain("UserService/getBalance")
      targets should contain("UserService/deposit")
      targets should contain("UserService/getHistory")
    }

    "detects client usages" in {
      val clientIntegrations = grpcIntegrations.filter(_.accessType == DataAccessType.Read)
      clientIntegrations should not be empty

      val targets = clientIntegrations.map(_.target).toSet
      targets should contain("RateService/getRate")
    }

    "server integrations are Write, client integrations are Read" in {
      val server = grpcIntegrations.filter(_.target.startsWith("UserService/"))
      server.foreach(_.accessType shouldBe DataAccessType.Write)

      val client = grpcIntegrations.filter(_.target.startsWith("RateService/"))
      client.foreach(_.accessType shouldBe DataAccessType.Read)
    }

    "all grpc integrations have integrationType grpc" in {
      grpcIntegrations.foreach(_.integrationType shouldBe "grpc")
    }

    "client usage is attributed to the correct method" in {
      val depositClientCalls = grpcIntegrations.filter { di =>
        di.accessType == DataAccessType.Read && di.method.methodName == "deposit"
      }
      depositClientCalls should have size 1
      depositClientCalls.head.target shouldBe "RateService/getRate"
    }

    "gRPC integrations have group set to service name" in {
      val userServiceIntegrations = grpcIntegrations.filter(_.target.startsWith("UserService/"))
      userServiceIntegrations should not be empty
      userServiceIntegrations.foreach(_.group shouldBe Some("UserService"))

      val rateServiceIntegrations = grpcIntegrations.filter(_.target.startsWith("RateService/"))
      rateServiceIntegrations should not be empty
      rateServiceIntegrations.foreach(_.group shouldBe Some("RateService"))
    }
  }

  "ManualScanner" - {

    "produces kafka integrations with correct fields" in {
      val manual = ManualScanner.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .build.scan(Nil)

      manual should have size 1
      val di = manual.head
      di.method shouldBe MethodRef("EventPublisher", "publishDeposit")
      di.accessType shouldBe DataAccessType.Write
      di.integrationType shouldBe "kafka"
      di.target shouldBe "user.deposit-events"
      di.evidence shouldBe "manual declaration"
      di.group shouldBe Some("Kafka")
    }

    "uses default Kafka cluster as group" in {
      val manual = ManualScanner.builder
        .method[UserRepo](_.getBalance).reads.kafka("some.topic")
        .build.scan(Nil)

      manual.head.group shouldBe Some("Kafka")
    }

    "supports custom cluster name" in {
      val manual = ManualScanner.builder
        .method[UserRepo](_.getBalance).reads.kafka("analytics.events", cluster = "Analytics")
        .build.scan(Nil)

      manual.head.group shouldBe Some("Analytics")
    }

    "supports generic custom integration type" in {
      val manual = ManualScanner.builder
        .method[UserRepo](_.getBalance).writes.custom("s3", "my-bucket/exports", group = Some("S3"))
        .build.scan(Nil)

      val di = manual.head
      di.integrationType shouldBe "s3"
      di.target shouldBe "my-bucket/exports"
      di.group shouldBe Some("S3")
    }

    "composes with automatic scanner results in LineageBuilder" in {
      val manualIntegrations = ManualScanner.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .build.scan(Nil)

      val allIntegrations = enrichment.enrich(doobieIntegrations ++ grpcIntegrations ++ manualIntegrations)
      val resultWithManual = LineageBuilder.build(callGraph, allIntegrations)

      val depositChains = resultWithManual.lineageFrom(MethodRef("UserGrpcApi", "deposit"))
      val kafkaChains = depositChains.filter(_.integration.integrationType == "kafka")
      kafkaChains should have size 1
      kafkaChains.head.integration.target shouldBe "user.deposit-events"
      kafkaChains.head.integration.accessType shouldBe DataAccessType.Write
      kafkaChains.head.path.map(_.className) shouldBe List("UserGrpcApi", "EventPublisher")
    }

    "supports multiple declarations in a single builder" in {
      val manual = ManualScanner.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .method[UserGrpcApi](_.getHistory).reads.kafka("user.history-events", cluster = "Analytics")
        .method[UserService](_.deposit).writes.custom("audit", "audit-log", group = Some("Audit"))
        .build.scan(Nil)

      manual should have size 3
      manual.count(_.integrationType == "kafka") shouldBe 2
      manual.count(_.integrationType == "audit") shouldBe 1
    }
  }

  "TastyPekkoJournalScanner" - {

    val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
    val pekkoIntegrations = new TastyPekkoJournalScanner().scan(List(pekkoPkg))

    "detects classic PersistentActor as Write to journal" in {
      val classicWrites = pekkoIntegrations.filter { di =>
        di.method.className == "OrderActor" && di.accessType == DataAccessType.Write
      }
      classicWrites should have size 1
      classicWrites.head.target shouldBe "journal"
      classicWrites.head.evidence shouldBe "extends PersistentActor"
    }

    "detects typed EventSourcedBehavior as Write to journal" in {
      val typedWrites = pekkoIntegrations.filter { di =>
        di.method.className == "AccountBehavior" && di.accessType == DataAccessType.Write
      }
      typedWrites should have size 1
      typedWrites.head.target shouldBe "journal"
      typedWrites.head.evidence shouldBe "calls EventSourcedBehavior"
    }

    "detects journal query usage as Read from journal" in {
      val reads = pekkoIntegrations.filter { di =>
        di.accessType == DataAccessType.Read && di.method.className == "EventProjection"
      }
      reads should have size 1
      reads.head.method.methodName shouldBe "streamByTag"
      reads.head.target shouldBe "journal"
    }

    "detects EventSourcedProvider usage as Read from journal" in {
      val espReads = pekkoIntegrations.filter { di =>
        di.method.className == "TagBasedProjection" && di.accessType == DataAccessType.Read
      }
      espReads should have size 1
      espReads.head.method.methodName shouldBe "createSource"
      espReads.head.evidence should include("EventSourcedProvider")
    }

    "detects PersistenceQuery.readJournalFor as Read from journal" in {
      val pqReads = pekkoIntegrations.filter { di =>
        di.method.className == "QueryBasedProjection" && di.accessType == DataAccessType.Read
      }
      pqReads should have size 1
      pqReads.head.method.methodName shouldBe "createReader"
      pqReads.head.evidence should include("readJournalFor")
    }

    "all pekko integrations have integrationType pekko-journal" in {
      pekkoIntegrations.foreach(_.integrationType shouldBe "pekko-journal")
    }

    "all pekko integrations have group Journal" in {
      pekkoIntegrations.foreach(_.group shouldBe Some("Journal"))
    }

    "composes with LineageBuilder" in {
      val pekkoCallGraph = new TastyCallGraphExtractor().extract(pekkoPkg)
      val pekkoResult = LineageBuilder.build(pekkoCallGraph, pekkoIntegrations)

      pekkoResult.integrations should have size pekkoIntegrations.size
      val output = pekkoResult.prettyPrint
      println(output)
      output should include("pekko-journal")
    }
  }

  "TastySlickScanner" - {

    val slickPkg = "domaindocs4s.architecture.lineage.example.slick"
    val slickIntegrations = new TastySlickScanner().scan(List(slickPkg))

    "outputs DiscoveredIntegration with integrationType slick" in {
      slickIntegrations should not be empty
      slickIntegrations.foreach { di =>
        di.integrationType shouldBe "slick"
        di.method.className should not be empty
        di.method.methodName should not be empty
        di.target should not be "unknown"
      }
    }

    "detects table names from lifted embedding operations" in {
      val tables = slickIntegrations.map(_.target).toSet
      tables should contain("account_balances")
      tables should contain("slick_transactions")
    }

    "classifies reads and writes correctly" in {
      val reads = slickIntegrations.filter(_.accessType == DataAccessType.Read)
      val writes = slickIntegrations.filter(_.accessType == DataAccessType.Write)

      reads.map(_.method.methodName).toSet should contain allOf ("getBalance", "listTransactions")
      writes.map(_.method.methodName).toSet should contain allOf ("upsertBalance", "insertTransactions", "deleteTransaction")
    }

    "detects sql interpolation as Read" in {
      val sqlReads = slickIntegrations.filter { di =>
        di.method.methodName == "getBalancePlainSql" && di.accessType == DataAccessType.Read
      }
      sqlReads should have size 1
      sqlReads.head.target shouldBe "account_balances"
      sqlReads.head.evidence should include("SELECT")
    }

    "detects sqlu interpolation as Write" in {
      val sqlWrites = slickIntegrations.filter { di =>
        di.method.methodName == "updateBalancePlainSql" && di.accessType == DataAccessType.Write
      }
      sqlWrites should have size 1
      sqlWrites.head.target shouldBe "account_balances"
      sqlWrites.head.evidence should include("UPDATE")
    }

    "composes with LineageBuilder" in {
      val slickCallGraph = new TastyCallGraphExtractor().extract(slickPkg)
      val slickResult = LineageBuilder.build(slickCallGraph, slickIntegrations)

      slickResult.integrations should have size slickIntegrations.size
      val output = slickResult.prettyPrint
      println(output)
      output should include("slick")
    }
  }

  "MermaidRenderer class-level" - {

    // Build a result that includes kafka (manual) integrations, matching RenderLineage
    val manualIntegrations = ManualScanner.builder
      .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
      .build.scan(Nil)
    val allIntegrations = enrichment.enrich(doobieIntegrations ++ grpcIntegrations ++ manualIntegrations)
    val resultWithManual = LineageBuilder.build(callGraph, allIntegrations)

    "contains class names as nodes, not individual methods" in {
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual)

      diagram should include("""["UserGrpcApi"]""")
      diagram should include("""["UserService"]""")
      diagram should include("""["UserRepo"]""")
      diagram should include("""["EventPublisher"]""")

      // Should not contain method-level nodes
      diagram should not include """["getBalance"]"""
      diagram should not include """["deposit"]"""
      diagram should not include """["getHistory"]"""
    }

    "folds gRPC endpoints by service" in {
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual)

      // Should contain folded service nodes
      diagram should include("UserService (ext)")
      diagram should include("RateService")

      // Should not contain individual gRPC endpoints
      diagram should not include "UserService/getBalance"
      diagram should not include "UserService/deposit"
      diagram should not include "RateService/getRate"
    }

    "keeps DB tables as individual nodes" in {
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual)

      diagram should include("users")
      diagram should include("transactions")
    }

    "keeps Kafka topics as individual nodes" in {
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual)

      diagram should include("user.deposit-events")
    }

    "deduplicates class-to-class call edges" in {
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual)
      val lines = diagram.split("\n")

      // UserGrpcApi calls multiple methods on UserService, but should appear as one edge
      lines.count(_.contains("cls_UserGrpcApi --> cls_UserService")) shouldBe 1
      lines.count(_.contains("cls_UserService --> cls_UserRepo")) shouldBe 1
    }

    "hides specified classes from diagram" in {
      val config = ClassLevelConfig.builder.hide[UserRepo].build
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual, config)

      diagram should not include """["UserRepo"]"""
      diagram should include("""["UserGrpcApi"]""")
      diagram should include("""["UserService"]""")
      diagram should include("""["EventPublisher"]""")
    }

    "promotes integrations from hidden class to callers" in {
      val config = ClassLevelConfig.builder.hide[UserRepo].build
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual, config)

      // UserRepo's DB integrations should be promoted to UserService
      diagram should include("cls_UserService")
      diagram should include("ext_users")
      diagram should include("ext_transactions")

      // No edges from hidden UserRepo
      diagram should not include "cls_UserRepo"
    }

    "removes call edges to hidden classes" in {
      val config = ClassLevelConfig.builder.hide[UserRepo].build
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual, config)

      diagram should not include "cls_UserService --> cls_UserRepo"
      // Other call edges remain
      diagram should include("cls_UserGrpcApi --> cls_UserService")
      diagram should include("cls_UserGrpcApi --> cls_EventPublisher")
    }

    "groups classes into subgraphs with custom grouping" in {
      val config = ClassLevelConfig.builder
        .groupClassesBy { cls =>
          if (cls.name.startsWith("User")) Some("user-domain")
          else Some("events")
        }
        .build
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual, config)

      diagram should include("""subgraph pkg_user_domain ["user-domain"]""")
      diagram should include("""subgraph pkg_events ["events"]""")
      diagram should include("""cls_UserGrpcApi["UserGrpcApi"]""")
      diagram should include("""cls_EventPublisher["EventPublisher"]""")
    }

    "ungrouped classes render as standalone nodes" in {
      val config = ClassLevelConfig.builder
        .groupClassesBy { cls =>
          if (cls.name == "UserGrpcApi") Some("api") else None
        }
        .build
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual, config)

      diagram should include("""subgraph pkg_api ["api"]""")
      // Classes outside the subgraph should still appear as standalone
      diagram should include("""cls_UserService["UserService"]""")
      // Standalone nodes should NOT be inside the subgraph
      val lines = diagram.split("\n")
      val subgraphStart = lines.indexWhere(_.contains("subgraph pkg_api"))
      val subgraphEnd = lines.indexWhere(l => l.trim == "end", subgraphStart)
      val subgraphBlock = lines.slice(subgraphStart, subgraphEnd + 1).mkString("\n")
      subgraphBlock should not include "UserService"
    }

    "ByPackage with same package produces no grouping" in {
      val config = ClassLevelConfig.builder
        .groupByPackage(pkg)
        .build
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual, config)

      diagram should not include "subgraph pkg_"
      // Classes should still render as standalone nodes
      diagram should include("""cls_UserGrpcApi["UserGrpcApi"]""")
    }
  }

  "TastyCallGraphExtractor" - {

    "discovers all three classes" in {
      val classNames = callGraph.map(_.className).distinct
      classNames should contain("UserRepo")
      classNames should contain("UserService")
      classNames should contain("UserGrpcApi")
    }

    "extracts call graph from UserService to UserRepo" in {
      val serviceCalls = result.callGraph.filter(_.caller.className == "UserService")
      val calledMethods = serviceCalls.map(e => (e.callee.className, e.callee.methodName))

      calledMethods should contain(("UserRepo", "getBalance"))
      calledMethods should contain(("UserRepo", "getTransactions"))
      calledMethods should contain(("UserRepo", "updateBalance"))
      calledMethods should contain(("UserRepo", "insertTransaction"))
    }

    "extracts call graph from UserGrpcApi to UserService" in {
      val apiCalls = result.callGraph.filter(_.caller.className == "UserGrpcApi")
      val calledMethods = apiCalls.map(e => (e.callee.className, e.callee.methodName))

      calledMethods should contain(("UserService", "getBalance"))
      calledMethods should contain(("UserService", "deposit"))
      calledMethods should contain(("UserService", "getHistory"))
    }
  }

  "LineageBuilder" - {

    "propagates effective access types" in {
      // UserGrpcApi.getBalance: grpc Write (server) + doobie Read (transitive) → ReadWrite
      val apiGetBalance = result.findMethod(MethodRef("UserGrpcApi", "getBalance"))
      apiGetBalance.map(_.effectiveAccess) shouldBe Some(DataAccessType.ReadWrite)

      // UserGrpcApi.deposit: grpc Write (server) + grpc Read (client) + doobie Write (transitive) → ReadWrite
      val apiDeposit = result.findMethod(MethodRef("UserGrpcApi", "deposit"))
      apiDeposit.map(_.effectiveAccess) shouldBe Some(DataAccessType.ReadWrite)
    }

    "builds lineage chains from API to DB and gRPC" in {
      result.lineageChains should not be empty

      // getBalance chains: 1 doobie + 1 grpc server = 2
      val balanceChains = result.lineageFrom(MethodRef("UserGrpcApi", "getBalance"))
      balanceChains should have size 2

      val balanceDoobieChains = balanceChains.filter(_.integration.integrationType == "doobie")
      balanceDoobieChains should have size 1
      balanceDoobieChains.head.integration.target shouldBe "users"
      balanceDoobieChains.head.integration.accessType shouldBe DataAccessType.Read
      balanceDoobieChains.head.path.map(_.className) shouldBe List("UserGrpcApi", "UserService", "UserRepo")

      val balanceGrpcChains = balanceChains.filter(_.integration.integrationType == "grpc")
      balanceGrpcChains should have size 1
      balanceGrpcChains.head.integration.target shouldBe "UserService/getBalance"

      // deposit chains: 2 doobie + 1 grpc server + 1 grpc client = 4
      val depositChains = result.lineageFrom(MethodRef("UserGrpcApi", "deposit"))
      depositChains should have size 4
      depositChains.filter(_.integration.integrationType == "doobie").map(_.integration.target).toSet shouldBe Set("users", "transactions")
      depositChains.filter(_.integration.integrationType == "grpc").map(_.integration.target).toSet shouldBe Set("UserService/deposit", "RateService/getRate")
    }

    "pretty prints the full result" in {
      val output = result.prettyPrint
      println(output)
      output should include("UserRepo")
      output should include("UserService")
      output should include("UserGrpcApi")
      output should include("doobie")
      output should include("grpc")
    }
  }
}
