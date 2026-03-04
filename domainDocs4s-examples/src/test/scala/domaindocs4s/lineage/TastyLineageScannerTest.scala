package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.example.{EventPublisher, UserGrpcApi, UserRepo, UserService}
import domaindocs4s.architecture.lineage.example.pekko.{KafkaFlexiFlowProducer, KafkaPlainSinkProducer}
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

import java.nio.file.Paths

class TastyLineageScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"
  private val ph  = f"${pkg.hashCode.abs}%08x".take(8) // package hash for node ID assertions

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
        di.resourceType shouldBe "database"
        di.scanner shouldBe "doobie"
        di.method.className should not be empty
        di.method.methodName should not be empty
        di.target should not be "unknown"
      }
    }

    "detects doobie integrations in UserRepo" in {
      doobieIntegrations.foreach(_.scanner shouldBe "doobie")

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
      val enrichedDoobie = integrations.filter(_.scanner == "doobie")
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

    "all grpc integrations have resourceType grpc and scanner grpc" in {
      grpcIntegrations.foreach { di =>
        di.resourceType shouldBe "grpc"
        di.scanner shouldBe "grpc"
      }
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

    "produces kafka entries with correct fields" in {
      val entries = ManualScanner.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .build.entries

      entries should have size 1
      val e = entries.head
      e.className shouldBe "EventPublisher"
      e.methodName shouldBe Some("publishDeposit")
      e.accessType shouldBe DataAccessType.Write
      e.resourceType shouldBe "kafka"
      e.target shouldBe "user.deposit-events"
      e.group shouldBe Some("Kafka")
      e.strict shouldBe true
    }

    "uses default Kafka cluster as group" in {
      val entries = ManualScanner.builder
        .method[UserRepo](_.getBalance).reads.kafka("some.topic")
        .build.entries

      entries.head.group shouldBe Some("Kafka")
    }

    "supports custom group via custom method" in {
      val entries = ManualScanner.builder
        .method[UserRepo](_.getBalance).reads.custom("kafka", "analytics.events", group = Some("Analytics"))
        .build.entries

      entries.head.group shouldBe Some("Analytics")
    }

    "supports generic custom resource type" in {
      val entries = ManualScanner.builder
        .method[UserRepo](_.getBalance).writes.custom("s3", "my-bucket/exports", group = Some("S3"))
        .build.entries

      val e = entries.head
      e.resourceType shouldBe "s3"
      e.target shouldBe "my-bucket/exports"
      e.group shouldBe Some("S3")
    }

    "composes with automatic scanner results in LineageBuilder" in {
      val manual = ManualScanner.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events").lenient
        .build

      val manualIntegrations = manual.apply(Nil)
      val allIntegrations = enrichment.enrich(doobieIntegrations ++ grpcIntegrations ++ manualIntegrations)
      val resultWithManual = LineageBuilder.build(callGraph, allIntegrations)

      val depositChains = resultWithManual.lineageFrom(MethodRef(pkg, "UserGrpcApi", "deposit"))
      val kafkaChains = depositChains.filter(_.integration.resourceType == "kafka")
      kafkaChains should have size 1
      kafkaChains.head.integration.target shouldBe "user.deposit-events"
      kafkaChains.head.integration.accessType shouldBe DataAccessType.Write
      kafkaChains.head.path.map(_.className) shouldBe List("UserGrpcApi", "EventPublisher")
    }

    "supports multiple declarations in a single builder" in {
      val entries = ManualScanner.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .method[UserGrpcApi](_.getHistory).reads.custom("kafka", "user.history-events", group = Some("Analytics"))
        .method[UserService](_.deposit).writes.custom("audit", "audit-log", group = Some("Audit"))
        .build.entries

      entries should have size 3
      entries.count(_.resourceType == "kafka") shouldBe 2
      entries.count(_.resourceType == "audit") shouldBe 1
    }

    "lenient marks all entries from the same chain as non-strict" in {
      val entries = ManualScanner.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("topic-a")
        .cls[KafkaFlexiFlowProducer].writes.kafka("topic-b").kafka("topic-c").lenient
        .build.entries

      entries(0).strict shouldBe true
      entries(1).strict shouldBe false
      entries(2).strict shouldBe false
    }

    "cls builder creates class-level entries via chaining" in {
      val entries = ManualScanner.builder
        .cls[KafkaFlexiFlowProducer].writes.kafka("topic.a").kafka("topic.b")
        .build.entries

      entries should have size 2
      entries.foreach { e =>
        e.className shouldBe "KafkaFlexiFlowProducer"
        e.methodName shouldBe None
        e.accessType shouldBe DataAccessType.Write
        e.resourceType shouldBe "kafka"
        e.group shouldBe Some("Kafka")
      }
      entries.map(_.target).toSet shouldBe Set("topic.a", "topic.b")
    }

    "transitions from IntegrationBuilder to new declaration" in {
      val entries = ManualScanner.builder
        .cls[KafkaFlexiFlowProducer].writes.kafka("topic.a")
        .method[EventPublisher](_.publishDeposit).writes.kafka("topic.b")
        .build.entries

      entries should have size 2
      entries(0).className shouldBe "KafkaFlexiFlowProducer"
      entries(0).methodName shouldBe None
      entries(1).className shouldBe "EventPublisher"
      entries(1).methodName shouldBe Some("publishDeposit")
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

    "detects PersistenceQuery usage as Read from journal" in {
      val pqReads = pekkoIntegrations.filter { di =>
        di.method.className == "QueryBasedProjection" && di.accessType == DataAccessType.Read
      }
      pqReads should have size 1
      pqReads.head.method.methodName shouldBe "createReader"
      pqReads.head.evidence should include("PersistenceQuery")
    }

    "all pekko integrations have resourceType journal and scanner pekko-journal" in {
      pekkoIntegrations.foreach { di =>
        di.resourceType shouldBe "journal"
        di.scanner shouldBe "pekko-journal"
      }
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

    "outputs DiscoveredIntegration with resourceType database and scanner slick" in {
      slickIntegrations should not be empty
      slickIntegrations.foreach { di =>
        di.resourceType shouldBe "database"
        di.scanner shouldBe "slick"
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
      output should include("database")
    }
  }

  "MermaidRenderer class-level" - {

    // Build a result that includes kafka (manual) integrations, matching RenderLineage
    val manualIntegrations = ManualScanner.builder
      .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events").lenient
      .build.apply(Nil)
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
      lines.count(_.contains(s"cls_${ph}_UserGrpcApi --> cls_${ph}_UserService")) shouldBe 1
      lines.count(_.contains(s"cls_${ph}_UserService --> cls_${ph}_UserRepo")) shouldBe 1
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
      diagram should include(s"cls_${ph}_UserService")
      diagram should include("ext_users")
      diagram should include("ext_transactions")

      // No edges from hidden UserRepo
      diagram should not include s"cls_${ph}_UserRepo"
    }

    "removes call edges to hidden classes" in {
      val config = ClassLevelConfig.builder.hide[UserRepo].build
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual, config)

      diagram should not include s"cls_${ph}_UserService --> cls_${ph}_UserRepo"
      // Other call edges remain
      diagram should include(s"cls_${ph}_UserGrpcApi --> cls_${ph}_UserService")
      diagram should include(s"cls_${ph}_UserGrpcApi --> cls_${ph}_EventPublisher")
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
      diagram should include(s"""cls_${ph}_UserGrpcApi["UserGrpcApi"]""")
      diagram should include(s"""cls_${ph}_EventPublisher["EventPublisher"]""")
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
      diagram should include(s"""cls_${ph}_UserService["UserService"]""")
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
      diagram should include(s"""cls_${ph}_UserGrpcApi["UserGrpcApi"]""")
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

  "FlywayMigrationScanner" - {

    val flywayDir = Paths.get(getClass.getClassLoader.getResource("flyway").toURI)
    val flywayScanner = new FlywayMigrationScanner(flywayDir, group = Some("core-db"))
    val flywayIntegrations = flywayScanner.scan()

    "discovers CREATE TABLE statements as Write" in {
      val creates = flywayIntegrations.filter { di =>
        di.accessType == DataAccessType.Write && di.evidence == "V001__create_tables.sql"
      }
      creates.map(_.target).toSet should contain allOf ("users", "transactions")
    }

    "discovers ALTER TABLE as Write" in {
      val alters = flywayIntegrations.filter(_.evidence == "V003__alter_tables.sql")
      alters should have size 1
      alters.head.target shouldBe "users"
      alters.head.accessType shouldBe DataAccessType.Write
    }

    "discovers CREATE VIEW as Write to view and Read from source tables" in {
      val viewIntegrations = flywayIntegrations.filter(_.evidence == "V002__create_views.sql")

      val viewWrite = viewIntegrations.filter(di => di.accessType == DataAccessType.Write)
      viewWrite should have size 1
      viewWrite.head.target shouldBe "user_transaction_summary"

      val sourceReads = viewIntegrations.filter(di => di.accessType == DataAccessType.Read)
      sourceReads.map(_.target).toSet should contain allOf ("users", "transactions")
    }

    "all flyway integrations have resourceType database and scanner flyway" in {
      flywayIntegrations should not be empty
      flywayIntegrations.foreach { di =>
        di.resourceType shouldBe "database"
        di.scanner shouldBe "flyway"
      }
    }

    "all flyway integrations have group core-db" in {
      flywayIntegrations.foreach(_.group shouldBe Some("core-db"))
    }

    "evidence includes filename" in {
      flywayIntegrations.foreach { di =>
        di.evidence should endWith(".sql")
      }
    }

    "method refs use flyway class and version" in {
      flywayIntegrations.foreach { di =>
        di.method.className shouldBe "flyway"
        di.method.methodName should startWith("V")
      }
    }

    "merges with doobie integrations into resources" in {
      val allIntegrations = doobieIntegrations ++ flywayIntegrations
      val resources = DiscoveredResource.merge(allIntegrations)

      // "users" table from doobie (group=None) and flyway (group=Some("core-db")) stay separate
      // because group differs
      val usersResources = resources.filter(_.target == "users")
      usersResources should not be empty

      // But if groups matched, they would merge
      val ungroupedFlyway = new FlywayMigrationScanner(flywayDir).scan()
      val doobieAndFlyway = doobieIntegrations ++ ungroupedFlyway
      val merged = DiscoveredResource.merge(doobieAndFlyway)
      val usersResource = merged.filter(r => r.target == "users" && r.group.isEmpty)
      usersResource should have size 1
      usersResource.head.discoveries.map(_.scanner).toSet should contain allOf ("doobie", "flyway")
    }
  }

  "TastyPekkoKafkaScanner" - {

    val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
    val kafkaIntegrations = new TastyPekkoKafkaScanner().scan(List(pekkoPkg))

    "detects Producer usage (flexiFlow) as Write to kafka" in {
      val flexiFlowWrites = kafkaIntegrations.filter { di =>
        di.method.className == "KafkaFlexiFlowProducer" && di.accessType == DataAccessType.Write
      }
      flexiFlowWrites should have size 1
      flexiFlowWrites.head.resourceType shouldBe "kafka"
      flexiFlowWrites.head.evidence should include("Producer")
    }

    "detects Producer usage (plainSink) as Write to kafka" in {
      val plainSinkWrites = kafkaIntegrations.filter { di =>
        di.method.className == "KafkaPlainSinkProducer" && di.accessType == DataAccessType.Write
      }
      plainSinkWrites should have size 1
      plainSinkWrites.head.resourceType shouldBe "kafka"
      plainSinkWrites.head.evidence should include("Producer")
    }

    "all pekko-kafka integrations have scanner pekko-kafka and group Kafka" in {
      val kafkaOnly = kafkaIntegrations.filter(_.scanner == "pekko-kafka")
      kafkaOnly should not be empty
      kafkaOnly.foreach { di =>
        di.resourceType shouldBe "kafka"
        di.group shouldBe Some("Kafka")
      }
    }

    "target includes class and method name as placeholder" in {
      val flexiFlow = kafkaIntegrations.find(_.method.className == "KafkaFlexiFlowProducer")
      flexiFlow.get.target should include("KafkaFlexiFlowProducer")
      flexiFlow.get.target should include("createFlow")
    }
  }

  "ManualDeclarations" - {

    "method-level override replaces target when match exists" in {
      val autoDetected = List(
        DiscoveredIntegration(
          method = MethodRef("", "MyProducer", "send"),
          accessType = DataAccessType.Write,
          resourceType = "kafka",
          scanner = "pekko-kafka",
          target = "unknown topic from MyProducer.send",
          evidence = "calls Producer.plainSink",
          group = Some("Kafka"),
        ),
      )

      val manual = ManualDeclarations(
        entries = List(
          ManualEntry("", "MyProducer", Some("send"), DataAccessType.Write, "kafka", "my-actual-topic", Some("Kafka")),
        ),
      )

      val result = manual.apply(autoDetected)
      result should have size 1
      result.head.target shouldBe "my-actual-topic"
      result.head.scanner shouldBe "manual"
      result.head.evidence shouldBe "manual override"
    }

    "lenient method-level adds when no match exists" in {
      val autoDetected = List(
        DiscoveredIntegration(
          method = MethodRef("", "MyProducer", "send"),
          accessType = DataAccessType.Write,
          resourceType = "kafka",
          scanner = "pekko-kafka",
          target = "unknown topic from MyProducer.send",
          evidence = "calls Producer.plainSink",
          group = Some("Kafka"),
        ),
      )

      val manual = ManualDeclarations(
        entries = List(
          ManualEntry("", "OtherClass", Some("publish"), DataAccessType.Write, "kafka", "other-topic", Some("Kafka"), strict = false),
        ),
      )

      val result = manual.apply(autoDetected)
      result should have size 2
      result.map(_.target).toSet shouldBe Set("unknown topic from MyProducer.send", "other-topic")
    }

    "strict method-level throws when no match exists" in {
      val autoDetected = List(
        DiscoveredIntegration(
          method = MethodRef("", "MyProducer", "send"),
          accessType = DataAccessType.Write,
          resourceType = "kafka",
          scanner = "pekko-kafka",
          target = "unknown topic from MyProducer.send",
          evidence = "calls Producer.plainSink",
          group = Some("Kafka"),
        ),
      )

      val manual = ManualDeclarations(
        entries = List(
          ManualEntry("", "NonExistent", Some("publish"), DataAccessType.Write, "kafka", "topic", Some("Kafka")),
        ),
      )

      val error = intercept[ManualOverrideError] {
        manual.apply(autoDetected)
      }
      error.unmatched should have size 1
      error.unmatched.head.className shouldBe "NonExistent"
    }

    "class-level override replaces all of resourceType with cross-product" in {
      val autoDetected = List(
        DiscoveredIntegration(
          method = MethodRef("", "Handler", "methodA"),
          accessType = DataAccessType.Write,
          resourceType = "kafka",
          scanner = "pekko-kafka",
          target = "unknown topic from Handler.methodA",
          evidence = "calls Producer.flexiFlow",
          group = Some("Kafka"),
        ),
        DiscoveredIntegration(
          method = MethodRef("", "Handler", "methodB"),
          accessType = DataAccessType.Write,
          resourceType = "kafka",
          scanner = "pekko-kafka",
          target = "unknown topic from Handler.methodB",
          evidence = "calls Producer.plainSink",
          group = Some("Kafka"),
        ),
        DiscoveredIntegration(
          method = MethodRef("", "Handler", "query"),
          accessType = DataAccessType.Read,
          resourceType = "database",
          scanner = "doobie",
          target = "users",
          evidence = "SELECT * FROM users",
        ),
      )

      val manual = ManualDeclarations(
        entries = List(
          ManualEntry("", "Handler", None, DataAccessType.Write, "kafka", "topic.a", Some("Kafka")),
          ManualEntry("", "Handler", None, DataAccessType.Write, "kafka", "topic.b", Some("Kafka")),
        ),
      )

      val result = manual.apply(autoDetected)
      // 2 methods x 2 topics = 4 kafka + 1 database = 5
      result should have size 5
      val kafkaResults = result.filter(_.resourceType == "kafka")
      kafkaResults should have size 4
      kafkaResults.map(_.target).toSet shouldBe Set("topic.a", "topic.b")
      kafkaResults.foreach { di =>
        di.scanner shouldBe "manual"
        di.evidence shouldBe "manual override"
      }
      // database integration untouched
      val dbResults = result.filter(_.resourceType == "database")
      dbResults should have size 1
      dbResults.head.target shouldBe "users"
    }

    "strict class-level throws on unmatched override" in {
      val autoDetected = List(
        DiscoveredIntegration(
          method = MethodRef("", "Other", "m"),
          accessType = DataAccessType.Write,
          resourceType = "database",
          scanner = "doobie",
          target = "users",
          evidence = "INSERT INTO users",
        ),
      )

      val manual = ManualDeclarations(
        entries = List(
          ManualEntry("", "NonExistent", None, DataAccessType.Write, "kafka", "topic", Some("Kafka")),
        ),
      )

      val error = intercept[ManualOverrideError] {
        manual.apply(autoDetected)
      }
      error.unmatched should have size 1
      error.unmatched.head.className shouldBe "NonExistent"
    }

    "lenient class-level ignores unmatched override" in {
      val autoDetected = List(
        DiscoveredIntegration(
          method = MethodRef("", "Other", "m"),
          accessType = DataAccessType.Write,
          resourceType = "database",
          scanner = "doobie",
          target = "users",
          evidence = "INSERT INTO users",
        ),
      )

      val manual = ManualDeclarations(
        entries = List(
          ManualEntry("", "NonExistent", None, DataAccessType.Write, "kafka", "topic", Some("Kafka"), strict = false),
        ),
      )

      val result = manual.apply(autoDetected)
      result should have size 1
      result.head.target shouldBe "users"
    }

    "per-entry strictness: strict entry throws while lenient entry passes" in {
      val autoDetected = List(
        DiscoveredIntegration(
          method = MethodRef("", "MyProducer", "send"),
          accessType = DataAccessType.Write,
          resourceType = "kafka",
          scanner = "pekko-kafka",
          target = "unknown",
          evidence = "calls Producer",
          group = Some("Kafka"),
        ),
      )

      // First entry matches, second is lenient (no match but OK), third is strict (no match → error)
      val manual = ManualDeclarations(
        entries = List(
          ManualEntry("", "MyProducer", Some("send"), DataAccessType.Write, "kafka", "real-topic", Some("Kafka")),
          ManualEntry("", "OtherClass", Some("publish"), DataAccessType.Write, "kafka", "other-topic", Some("Kafka"), strict = false),
          ManualEntry("", "Missing", None, DataAccessType.Write, "kafka", "fail-topic", Some("Kafka")),
        ),
      )

      val error = intercept[ManualOverrideError] {
        manual.apply(autoDetected)
      }
      error.unmatched should have size 1
      error.unmatched.head.className shouldBe "Missing"
    }

    "empty ManualDeclarations passes through unchanged" in {
      val autoDetected = List(
        DiscoveredIntegration(
          method = MethodRef("", "A", "b"),
          accessType = DataAccessType.Read,
          resourceType = "database",
          scanner = "doobie",
          target = "users",
          evidence = "SELECT",
        ),
      )

      val result = ManualDeclarations.empty.apply(autoDetected)
      result shouldBe autoDetected
    }

    "PekkoKafkaScanner + ManualScanner override integration" in {
      val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
      val kafkaDetected = new TastyPekkoKafkaScanner().scan(List(pekkoPkg))

      val manual = ManualScanner.builder
        .cls[KafkaFlexiFlowProducer].writes.kafka("events.flexiflow-topic")
        .cls[KafkaPlainSinkProducer].writes.kafka("events.plainsink-topic")
        .build

      val result = manual.apply(kafkaDetected)
      val flexiFlowResults = result.filter(_.method.className == "KafkaFlexiFlowProducer")
      flexiFlowResults should have size 1
      flexiFlowResults.head.target shouldBe "events.flexiflow-topic"
      flexiFlowResults.head.scanner shouldBe "manual"

      val plainSinkResults = result.filter(_.method.className == "KafkaPlainSinkProducer")
      plainSinkResults should have size 1
      plainSinkResults.head.target shouldBe "events.plainsink-topic"
      plainSinkResults.head.scanner shouldBe "manual"
    }
  }

  "LineageBuilder" - {

    "propagates effective access types" in {
      // UserGrpcApi.getBalance: grpc Write (server) + doobie Read (transitive) → ReadWrite
      val apiGetBalance = result.findMethod(MethodRef(pkg, "UserGrpcApi", "getBalance"))
      apiGetBalance.map(_.effectiveAccess) shouldBe Some(DataAccessType.ReadWrite)

      // UserGrpcApi.deposit: grpc Write (server) + grpc Read (client) + doobie Write (transitive) → ReadWrite
      val apiDeposit = result.findMethod(MethodRef(pkg, "UserGrpcApi", "deposit"))
      apiDeposit.map(_.effectiveAccess) shouldBe Some(DataAccessType.ReadWrite)
    }

    "builds lineage chains from API to DB and gRPC" in {
      result.lineageChains should not be empty

      // getBalance chains: 1 doobie + 1 grpc server = 2
      val balanceChains = result.lineageFrom(MethodRef(pkg, "UserGrpcApi", "getBalance"))
      balanceChains should have size 2

      val balanceDoobieChains = balanceChains.filter(_.integration.scanner == "doobie")
      balanceDoobieChains should have size 1
      balanceDoobieChains.head.integration.target shouldBe "users"
      balanceDoobieChains.head.integration.accessType shouldBe DataAccessType.Read
      balanceDoobieChains.head.path.map(_.className) shouldBe List("UserGrpcApi", "UserService", "UserRepo")

      val balanceGrpcChains = balanceChains.filter(_.integration.scanner == "grpc")
      balanceGrpcChains should have size 1
      balanceGrpcChains.head.integration.target shouldBe "UserService/getBalance"

      // deposit chains: 2 doobie + 1 grpc server + 1 grpc client = 4
      val depositChains = result.lineageFrom(MethodRef(pkg, "UserGrpcApi", "deposit"))
      depositChains should have size 4
      depositChains.filter(_.integration.scanner == "doobie").map(_.integration.target).toSet shouldBe Set("users", "transactions")
      depositChains.filter(_.integration.scanner == "grpc").map(_.integration.target).toSet shouldBe Set("UserService/deposit", "RateService/getRate")
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
