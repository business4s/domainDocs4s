package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.LineageAdjustment
import domaindocs4s.architecture.lineage.example.{EventPublisher, S3Exporter, UserGrpcApi, UserRepo, UserService}
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

  "LineageAdjustments builder" - {

    "produces kafka adjustments with correct fields" in {
      val adj = LineageAdjustments.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .build

      val (_, result) = adj.apply(Nil, Nil)
      result should have size 1
      val e = result.head
      e.method.className shouldBe "EventPublisher"
      e.method.methodName shouldBe "publishDeposit"
      e.accessType shouldBe DataAccessType.Write
      e.resourceType shouldBe "kafka"
      e.target shouldBe "user.deposit-events"
      e.group shouldBe Some("Kafka")
      e.scanner shouldBe "manual"
    }

    "kafka uses default Kafka group" in {
      val adj = LineageAdjustments.builder
        .method[UserRepo](_.getBalance).reads.kafka("some.topic")
        .build

      val (_, result) = adj.apply(Nil, Nil)
      result.head.group shouldBe Some("Kafka")
    }

    "supports custom group via custom method" in {
      val adj = LineageAdjustments.builder
        .method[UserRepo](_.getBalance).reads.custom("kafka", "analytics.events", group = Some("Analytics"))
        .build

      val (_, result) = adj.apply(Nil, Nil)
      result.head.group shouldBe Some("Analytics")
    }

    "supports generic custom resource type" in {
      val adj = LineageAdjustments.builder
        .method[UserRepo](_.getBalance).writes.custom("s3", "my-bucket/exports", group = Some("S3"))
        .build

      val (_, result) = adj.apply(Nil, Nil)
      val e = result.head
      e.resourceType shouldBe "s3"
      e.target shouldBe "my-bucket/exports"
      e.group shouldBe Some("S3")
    }

    "produces s3 adjustments with correct fields via .s3()" in {
      val adj = LineageAdjustments.builder
        .method[S3Exporter](_.exportData).writes.s3("ledger-exports/assets")
        .build

      val (_, result) = adj.apply(Nil, Nil)
      result should have size 1
      val e = result.head
      e.method.className shouldBe "S3Exporter"
      e.method.methodName shouldBe "exportData"
      e.accessType shouldBe DataAccessType.Write
      e.resourceType shouldBe "s3"
      e.target shouldBe "ledger-exports/assets"
      e.group shouldBe Some("S3")
    }

    "composes with automatic scanner results in LineageBuilder" in {
      val adj = LineageAdjustments.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .build

      val (adjCallGraph, adjIntegrations) = adj.apply(callGraph, doobieIntegrations ++ grpcIntegrations)
      val allIntegrations = enrichment.enrich(adjIntegrations)
      val resultWithManual = LineageBuilder.build(adjCallGraph, allIntegrations)

      val depositChains = resultWithManual.lineageFrom(MethodRef(pkg, "UserGrpcApi", "deposit"))
      val kafkaChains = depositChains.filter(_.integration.resourceType == "kafka")
      kafkaChains should have size 1
      kafkaChains.head.integration.target shouldBe "user.deposit-events"
      kafkaChains.head.integration.accessType shouldBe DataAccessType.Write
      kafkaChains.head.path.map(_.className) shouldBe List("UserGrpcApi", "EventPublisher")
    }

    "supports multiple adjustments in a single builder" in {
      val adj = LineageAdjustments.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .method[UserGrpcApi](_.getHistory).reads.custom("kafka", "user.history-events", group = Some("Analytics"))
        .method[UserService](_.deposit).writes.custom("audit", "audit-log", group = Some("Audit"))
        .build

      val (_, result) = adj.apply(Nil, Nil)
      result should have size 3
      result.count(_.resourceType == "kafka") shouldBe 2
      result.count(_.resourceType == "audit") shouldBe 1
    }

    "cls builds class-level adjustments via chaining" in {
      val adj = LineageAdjustments.builder
        .cls[KafkaFlexiFlowProducer].writes.kafka("topic.a").kafka("topic.b")
        .build

      adj.adjustments should have size 2
      adj.adjustments.foreach { adj =>
        adj shouldBe a[LineageAdjustment.AddClassIntegration]
      }
    }

    "transitions from IntegrationBuilder to new selector" in {
      val adj = LineageAdjustments.builder
        .cls[KafkaFlexiFlowProducer].writes.kafka("topic.a")
        .method[EventPublisher](_.publishDeposit).writes.kafka("topic.b")
        .build

      adj.adjustments should have size 2
      adj.adjustments(0) shouldBe a[LineageAdjustment.AddClassIntegration]
      adj.adjustments(1) shouldBe a[LineageAdjustment.AddIntegration]
    }

    "class-level calls adds call edges (resolves to matching method)" in {
      val adj = LineageAdjustments.builder
        .cls[UserService].calls[EventPublisher](_.publishDeposit)
        .build

      val methods = List(
        ExtractedMethod("UserService", pkg, "deposit", List(MethodRef(pkg, "EventPublisher", "someOther"))),
        ExtractedMethod("UserService", pkg, "getBalance", Nil),
        ExtractedMethod("EventPublisher", pkg, "publishDeposit", Nil),
        ExtractedMethod("EventPublisher", pkg, "someOther", Nil),
      )
      val (resultMethods, _) = adj.apply(methods, Nil)
      // Should resolve to deposit (already calls EventPublisher)
      val deposit = resultMethods.find(_.ref == MethodRef(pkg, "UserService", "deposit")).get
      deposit.calls should contain(MethodRef(pkg, "EventPublisher", "publishDeposit"))
      // getBalance should not be affected
      val getBalance = resultMethods.find(_.ref == MethodRef(pkg, "UserService", "getBalance")).get
      getBalance.calls shouldBe empty
    }

    "class-level removesCall removes from all methods" in {
      val adj = LineageAdjustments.builder
        .cls[UserService].removesCall[UserRepo](_.getBalance)
        .build

      val methods = List(
        ExtractedMethod("UserService", pkg, "getBalance", List(MethodRef(pkg, "UserRepo", "getBalance"))),
        ExtractedMethod("UserService", pkg, "deposit", List(MethodRef(pkg, "UserRepo", "getBalance"), MethodRef(pkg, "UserRepo", "updateBalance"))),
        ExtractedMethod("UserRepo", pkg, "getBalance", Nil),
        ExtractedMethod("UserRepo", pkg, "updateBalance", Nil),
      )
      val (resultMethods, _) = adj.apply(methods, Nil)
      // Both methods should have the call removed
      val getBalance = resultMethods.find(_.ref == MethodRef(pkg, "UserService", "getBalance")).get
      getBalance.calls should not contain MethodRef(pkg, "UserRepo", "getBalance")
      val deposit = resultMethods.find(_.ref == MethodRef(pkg, "UserService", "deposit")).get
      deposit.calls should not contain MethodRef(pkg, "UserRepo", "getBalance")
      deposit.calls should contain(MethodRef(pkg, "UserRepo", "updateBalance"))
    }

    "method-level calls adds call edges" in {
      val adj = LineageAdjustments.builder
        .method[UserService](_.deposit).calls[EventPublisher](_.publishDeposit)
        .build

      val methods = List(
        ExtractedMethod("UserService", pkg, "deposit", Nil),
        ExtractedMethod("EventPublisher", pkg, "publishDeposit", Nil),
      )
      val (resultMethods, _) = adj.apply(methods, Nil)
      val serviceMethod = resultMethods.find(_.ref == MethodRef(pkg, "UserService", "deposit"))
      serviceMethod.get.calls should contain(MethodRef(pkg, "EventPublisher", "publishDeposit"))
    }

    "method-level .remove hides method and reconnects callers to callees" in {
      val adj = LineageAdjustments.builder
        .method[UserService](_.getBalance).remove
        .build

      val methods = List(
        ExtractedMethod("UserGrpcApi", pkg, "getBalance", List(MethodRef(pkg, "UserService", "getBalance"))),
        ExtractedMethod("UserService", pkg, "getBalance", List(MethodRef(pkg, "UserRepo", "getBalance"))),
        ExtractedMethod("UserRepo", pkg, "getBalance", Nil),
      )
      val existingIntegrations = List(
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )
      val (resultMethods, resultIntegrations) = adj.apply(methods, existingIntegrations)

      // Hidden method is gone
      resultMethods.map(_.ref) should not contain MethodRef(pkg, "UserService", "getBalance")
      // Caller now calls the hidden method's callee
      val apiMethod = resultMethods.find(_.ref.className == "UserGrpcApi").get
      apiMethod.calls should contain(MethodRef(pkg, "UserRepo", "getBalance"))
      // Existing integrations are untouched (they were on UserRepo, not the hidden method)
      resultIntegrations should have size 1
    }

    "method-level .remove promotes integrations from hidden method to callers" in {
      val adj = LineageAdjustments.builder
        .method[UserRepo](_.getBalance).remove
        .build

      val methods = List(
        ExtractedMethod("UserService", pkg, "getBalance", List(MethodRef(pkg, "UserRepo", "getBalance"))),
        ExtractedMethod("UserRepo", pkg, "getBalance", Nil),
      )
      val existingIntegrations = List(
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )
      val (resultMethods, resultIntegrations) = adj.apply(methods, existingIntegrations)

      resultMethods.map(_.ref) should not contain MethodRef(pkg, "UserRepo", "getBalance")
      // Integration promoted to the caller (UserService)
      resultIntegrations should have size 1
      resultIntegrations.head.method shouldBe MethodRef(pkg, "UserService", "getBalance")
      resultIntegrations.head.target shouldBe "users"
    }

    "class-level .remove hides class and reconnects callers to external callees" in {
      val adj = LineageAdjustments.builder
        .cls[UserRepo].remove
        .build

      val methods = List(
        ExtractedMethod("UserService", pkg, "getBalance", List(MethodRef(pkg, "UserRepo", "getBalance"))),
        ExtractedMethod("UserService", pkg, "deposit", List(MethodRef(pkg, "UserRepo", "updateBalance"))),
        ExtractedMethod("UserRepo", pkg, "getBalance", Nil),
        ExtractedMethod("UserRepo", pkg, "updateBalance", Nil),
      )
      val existingIntegrations = List(
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "updateBalance"), DataAccessType.Write, "database", "doobie", "users", "UPDATE"),
      )
      val (resultMethods, resultIntegrations) = adj.apply(methods, existingIntegrations)

      // Hidden class is gone
      resultMethods.map(_.className) should not contain "UserRepo"
      // Integrations promoted to the callers
      resultIntegrations should have size 2
      resultIntegrations.foreach(_.method.className shouldBe "UserService")
      resultIntegrations.map(_.method.methodName).toSet shouldBe Set("getBalance", "deposit")
    }

    "method-level .delete hard-removes method and disconnects graph" in {
      val adj = LineageAdjustments.builder
        .method[UserRepo](_.getBalance).delete
        .build

      val methods = List(
        ExtractedMethod("UserService", pkg, "getBalance", List(MethodRef(pkg, "UserRepo", "getBalance"))),
        ExtractedMethod("UserRepo", pkg, "getBalance", Nil),
      )
      val existingIntegrations = List(
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )
      val (resultMethods, resultIntegrations) = adj.apply(methods, existingIntegrations)

      resultMethods.flatMap(_.calls) should not contain MethodRef(pkg, "UserRepo", "getBalance")
      resultMethods.map(_.ref) should not contain MethodRef(pkg, "UserRepo", "getBalance")
      resultIntegrations shouldBe empty
    }

    "class-level .delete hard-removes class and disconnects graph" in {
      val adj = LineageAdjustments.builder
        .cls[UserRepo].delete
        .build

      val methods = List(
        ExtractedMethod("UserService", pkg, "getBalance", List(MethodRef(pkg, "UserRepo", "getBalance"))),
        ExtractedMethod("UserRepo", pkg, "getBalance", Nil),
        ExtractedMethod("UserRepo", pkg, "updateBalance", Nil),
      )
      val existingIntegrations = List(
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "updateBalance"), DataAccessType.Write, "database", "doobie", "users", "UPDATE"),
      )
      val (resultMethods, resultIntegrations) = adj.apply(methods, existingIntegrations)

      resultMethods.map(_.className) should not contain "UserRepo"
      resultMethods.head.calls shouldBe empty
      resultIntegrations shouldBe empty
    }

    "resource().renameTo renames target across integrations" in {
      val adj = LineageAdjustments.builder
        .resource("kafka", "old-topic").renameTo("new-topic")
        .build

      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "m"), DataAccessType.Write, "kafka", "pekko-kafka", "old-topic", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "B", "n"), DataAccessType.Write, "kafka", "pekko-kafka", "other-topic", "evidence", Some("Kafka")),
      )
      val (_, result) = adj.apply(Nil, existing)
      result.find(_.method.className == "A").get.target shouldBe "new-topic"
      result.find(_.method.className == "B").get.target shouldBe "other-topic"
    }

    "resource().remove removes all integrations to target" in {
      val adj = LineageAdjustments.builder
        .resource("kafka", "old-topic").remove
        .build

      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "m"), DataAccessType.Write, "kafka", "pekko-kafka", "old-topic", "evidence"),
        DiscoveredIntegration(MethodRef("", "B", "n"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )
      val (_, result) = adj.apply(Nil, existing)
      result should have size 1
      result.head.target shouldBe "users"
    }

    "resource().setGroup changes group" in {
      val adj = LineageAdjustments.builder
        .resource("database", "users").setGroup("user-db")
        .build

      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "m"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )
      val (_, result) = adj.apply(Nil, existing)
      result.head.group shouldBe Some("user-db")
    }

    "class-level renameTo sets display name without affecting data" in {
      val adj = LineageAdjustments.builder
        .cls[UserRepo].renameTo("User Repository")
        .build

      adj.classRenames shouldBe Map((pkg, "UserRepo") -> "User Repository")
      // apply() does not modify data for renames
      val methods = List(ExtractedMethod("UserRepo", pkg, "getBalance", Nil))
      val (resultMethods, _) = adj.apply(methods, Nil)
      resultMethods.head.className shouldBe "UserRepo"
    }

    "supports database and grpc convenience methods" in {
      val adj = LineageAdjustments.builder
        .method[UserRepo](_.getBalance).reads.database("users", group = Some("user-db"))
        .method[UserGrpcApi](_.deposit).reads.grpc("RateService/getRate")
        .build

      val (_, result) = adj.apply(Nil, Nil)
      result should have size 2
      result(0).resourceType shouldBe "database"
      result(0).group shouldBe Some("user-db")
      result(1).resourceType shouldBe "grpc"
      result(1).group shouldBe Some("RateService")
    }

    "string-based selectors work like type-safe ones" in {
      val adj = LineageAdjustments.builder
        .method("com.example", "ExternalService", "call").writes.kafka("events")
        .cls("com.example", "InternalHelper").remove
        .build

      adj.adjustments should have size 2
    }

    "strict by default — class-level integration passes when scanner detected matching resourceType" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "send"), DataAccessType.Write, "kafka", "pekko-kafka", "auto-topic", "evidence"),
      )
      val methods = List(
        ExtractedMethod("Handler", "", "send", Nil),
      )

      val adj = LineageAdjustments.builder
        .cls("", "Handler").writes.kafka("real-topic")
        .build

      val (_, result) = adj.apply(methods, existing)
      // Should succeed — kafka was auto-detected on Handler
      result.count(_.scanner == "manual") shouldBe 1
      result.find(_.scanner == "manual").get.target shouldBe "real-topic"
    }

    "strict by default — passes when detection is on a callee reachable through call graph" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "LowLevel", "write"), DataAccessType.Write, "s3", "s3", "S3", "putObject"),
      )
      val methods = List(
        ExtractedMethod("Handler", "", "handle", List(MethodRef("", "Middle", "process"))),
        ExtractedMethod("Middle", "", "process", List(MethodRef("", "LowLevel", "write"))),
        ExtractedMethod("LowLevel", "", "write", Nil),
      )

      val adj = LineageAdjustments.builder
        .cls("", "Handler").writes.s3("exports-bucket")
        .build

      // Should not throw — s3 detected on LowLevel, reachable from Handler
      val (_, result) = adj.apply(methods, existing)
      result.count(_.scanner == "manual") shouldBe 1
    }

    "strict by default — throws when no matching resourceType is detected" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )
      val methods = List(
        ExtractedMethod("Handler", "", "query", Nil),
      )

      val adj = LineageAdjustments.builder
        .cls("", "Handler").writes.kafka("topic")
        .build

      val ex = intercept[IllegalStateException] {
        adj.apply(methods, existing)
      }
      ex.getMessage should include("kafka")
      ex.getMessage should include("Handler")
    }

    "strict by default — throws when class has no methods in call graph" in {
      val adj = LineageAdjustments.builder
        .cls("", "Ghost").writes.s3("bucket")
        .build

      val ex = intercept[IllegalStateException] {
        adj.apply(Nil, Nil)
      }
      ex.getMessage should include("s3")
      ex.getMessage should include("Ghost")
    }

    "builder-level .undetected opts out per resource type" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )
      val methods = List(
        ExtractedMethod("Handler", "", "query", Nil),
      )

      // kafka is not auto-detected, but .undetected("kafka") marks it as manual-only
      val adj = LineageAdjustments.builder
        .undetected("kafka")
        .cls("", "Handler").writes.kafka("topic")
        .cls("", "Handler").reads.database("users")
        .build

      // Should not throw — kafka is manual-only, database is detected
      val (_, result) = adj.apply(methods, existing)
      result.count(_.scanner == "manual") shouldBe 2
    }

    ".undetected on integration builder opts out for current entries only" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )
      val methods = List(
        ExtractedMethod("Handler", "", "query", Nil),
      )

      // First builder: .undetected — kafka not detected, manual-only
      // Second builder: default — database is detected, passes
      val adj = LineageAdjustments.builder
        .cls("", "Handler").writes.kafka("topic").undetected
        .cls("", "Handler").reads.database("users")
        .build

      val (_, result) = adj.apply(methods, existing)
      result.count(_.scanner == "manual") shouldBe 2
    }

    ".undetected on integration builder does not affect other builders" in {
      val methods = List(
        ExtractedMethod("Handler", "", "query", Nil),
      )

      // First builder: .undetected — kafka not detected, manual-only
      // Second builder: default — s3 not detected, should throw
      val adj = LineageAdjustments.builder
        .cls("", "Handler").writes.kafka("topic").undetected
        .cls("", "Handler").writes.s3("bucket")
        .build

      val ex = intercept[IllegalStateException] {
        adj.apply(methods, Nil)
      }
      ex.getMessage should include("s3")
    }

    ".detected overrides builder-level .undetected for specific entries" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "send"), DataAccessType.Write, "kafka", "pekko-kafka", "auto-topic", "evidence"),
      )
      val methods = List(
        ExtractedMethod("Handler", "", "send", Nil),
      )

      // Builder marks kafka as undetected, but .detected overrides for this entry
      val adj = LineageAdjustments.builder
        .undetected("kafka")
        .cls("", "Handler").writes.kafka("real-topic").detected
        .build

      // Should pass — kafka IS detected on Handler, and .detected requires it
      val (_, result) = adj.apply(methods, existing)
      result.count(_.scanner == "manual") shouldBe 1
    }

    ".detected override throws when detection is missing" in {
      val methods = List(
        ExtractedMethod("Handler", "", "send", Nil),
      )

      // Builder marks kafka as undetected, but .detected overrides — and there's no detection
      val adj = LineageAdjustments.builder
        .undetected("kafka")
        .cls("", "Handler").writes.kafka("topic").detected
        .build

      val ex = intercept[IllegalStateException] {
        adj.apply(methods, Nil)
      }
      ex.getMessage should include("kafka")
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

    "pekko integrations have no group by default" in {
      pekkoIntegrations.foreach(_.group shouldBe None)
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
    val adj = LineageAdjustments.builder
      .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
      .build
    val (adjCallGraph, adjIntegrations) = adj.apply(callGraph, doobieIntegrations ++ grpcIntegrations)
    val allIntegrations = enrichment.enrich(adjIntegrations)
    val resultWithManual = LineageBuilder.build(adjCallGraph, allIntegrations)

    // Build a result with UserRepo hidden via LineageAdjustments
    val adjWithHide = LineageAdjustments.builder
      .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
      .cls[UserRepo].remove
      .build
    val (hiddenCallGraph, hiddenIntegrations) = adjWithHide.apply(callGraph, doobieIntegrations ++ grpcIntegrations)
    val hiddenAllIntegrations = enrichment.enrich(hiddenIntegrations)
    val resultWithHidden = LineageBuilder.build(hiddenCallGraph, hiddenAllIntegrations)

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

    "renders renamed class with display name but preserves node ID" in {
      val renamed = resultWithManual.copy(
        classDisplayNames = Map((pkg, "UserRepo") -> "User Repository")
      )
      val diagram = MermaidRenderer.renderClassLevel(renamed)

      // Display name is used in the label
      diagram should include("""["User Repository"]""")
      // Original class name is no longer in any label
      diagram should not include """["UserRepo"]"""
      // Node ID still uses the original name
      diagram should include(s"cls_${ph}_UserRepo")
    }

    "hides specified classes from diagram via LineageAdjustments.remove" in {
      val diagram = MermaidRenderer.renderClassLevel(resultWithHidden)

      diagram should not include """["UserRepo"]"""
      diagram should include("""["UserGrpcApi"]""")
      diagram should include("""["UserService"]""")
      diagram should include("""["EventPublisher"]""")
    }

    "promotes integrations from hidden class to callers via LineageAdjustments.remove" in {
      val diagram = MermaidRenderer.renderClassLevel(resultWithHidden)

      // UserRepo's DB integrations should be promoted to UserService
      diagram should include(s"cls_${ph}_UserService")
      diagram should include("ext_users")
      diagram should include("ext_transactions")

      // No edges from hidden UserRepo
      diagram should not include s"cls_${ph}_UserRepo"
    }

    "removes call edges to hidden classes via LineageAdjustments.remove" in {
      val diagram = MermaidRenderer.renderClassLevel(resultWithHidden)

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

  "TastyS3Scanner" - {

    val s3Integrations = new TastyS3Scanner().scan(List(pkg))

    "detects S3 putObject as Write" in {
      val writes = s3Integrations.filter { di =>
        di.method.className == "S3Exporter" && di.accessType == DataAccessType.Write
      }
      writes should have size 1
      writes.head.target shouldBe "S3"
      writes.head.evidence should include("putObject")
    }

    "detects S3 getObject as Read" in {
      val reads = s3Integrations.filter { di =>
        di.method.className == "S3Reader" && di.accessType == DataAccessType.Read
      }
      reads should have size 1
      reads.head.target shouldBe "S3"
      reads.head.evidence should include("getObject")
    }

    "all S3 integrations have resourceType s3 and scanner s3" in {
      s3Integrations should not be empty
      s3Integrations.foreach { di =>
        di.resourceType shouldBe "s3"
        di.scanner shouldBe "s3"
      }
    }

    "all S3 integrations have group S3" in {
      s3Integrations.foreach(_.group shouldBe Some("S3"))
    }

    "LineageAdjustments .s3(bucket) overrides auto-detected S3 targets" in {
      val adj = LineageAdjustments.builder
        .cls[S3Exporter].removeIntegrations("s3")
        .cls[S3Exporter].writes.s3("ledger-exports/assets")
        .build

      val (_, result) = adj.apply(Nil, s3Integrations)
      val exporterResults = result.filter(_.method.className == "S3Exporter")
      exporterResults should have size 1
      exporterResults.head.target shouldBe "ledger-exports/assets"
      exporterResults.head.scanner shouldBe "manual"
      exporterResults.head.resourceType shouldBe "s3"
      exporterResults.head.group shouldBe Some("S3")

      // S3Reader integrations should be untouched
      val readerResults = result.filter(_.method.className == "S3Reader")
      readerResults should have size 1
      readerResults.head.target shouldBe "S3"
      readerResults.head.scanner shouldBe "s3"
    }
  }

  "LineageAdjustments apply" - {

    "addIntegration always adds to integration list" in {
      val existing = List(
        DiscoveredIntegration(
          method = MethodRef("", "MyProducer", "send"),
          accessType = DataAccessType.Write,
          resourceType = "kafka",
          scanner = "pekko-kafka",
          target = "auto-topic",
          evidence = "calls Producer.plainSink",
          group = Some("Kafka"),
        ),
      )

      val adj = LineageAdjustments.builder
        .method("", "OtherClass", "publish").writes.kafka("other-topic")
        .build

      val (_, result) = adj.apply(Nil, existing)
      result should have size 2
      result.map(_.target).toSet shouldBe Set("auto-topic", "other-topic")
    }

    "removeIntegration removes specific integration" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "MyProducer", "send"), DataAccessType.Write, "kafka", "pekko-kafka", "topic-a", "evidence"),
        DiscoveredIntegration(MethodRef("", "MyProducer", "send"), DataAccessType.Write, "kafka", "pekko-kafka", "topic-b", "evidence"),
      )

      val adj = LineageAdjustments.builder
        .method("", "MyProducer", "send").removeIntegration("kafka", "topic-a")
        .build

      val (_, result) = adj.apply(Nil, existing)
      result should have size 1
      result.head.target shouldBe "topic-b"
    }

    "removeIntegrationsByType removes all of a type" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "m"), DataAccessType.Write, "kafka", "pekko-kafka", "topic-a", "evidence"),
        DiscoveredIntegration(MethodRef("", "Handler", "m"), DataAccessType.Write, "kafka", "pekko-kafka", "topic-b", "evidence"),
        DiscoveredIntegration(MethodRef("", "Handler", "m"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )

      val adj = LineageAdjustments.builder
        .method("", "Handler", "m").removeIntegrations("kafka")
        .build

      val (_, result) = adj.apply(Nil, existing)
      result should have size 1
      result.head.resourceType shouldBe "database"
    }

    "addClassIntegration adds to methods with matching resourceType" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "methodA"), DataAccessType.Write, "kafka", "pekko-kafka", "unknown-a", "evidence"),
        DiscoveredIntegration(MethodRef("", "Handler", "methodB"), DataAccessType.Write, "kafka", "pekko-kafka", "unknown-b", "evidence"),
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )

      val adj = LineageAdjustments.builder
        .cls("", "Handler").writes.kafka("actual-topic")
        .build

      val (_, result) = adj.apply(Nil, existing)
      // Original 3 + 2 new (for methodA and methodB, matched by kafka resourceType)
      result should have size 5
      val manualKafka = result.filter(_.scanner == "manual")
      manualKafka should have size 2
      manualKafka.map(_.method.methodName).toSet shouldBe Set("methodA", "methodB")
      manualKafka.foreach(_.target shouldBe "actual-topic")
    }

    "removeIntegrations + addClassIntegration = override pattern" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "methodA"), DataAccessType.Write, "kafka", "pekko-kafka", "unknown-a", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "Handler", "methodB"), DataAccessType.Write, "kafka", "pekko-kafka", "unknown-b", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )

      val adj = LineageAdjustments.builder
        .cls("", "Handler").removeIntegrations("kafka")
        .cls("", "Handler").writes.kafka("topic.a").kafka("topic.b")
        .build

      val (_, result) = adj.apply(Nil, existing)
      // 1 database + 4 kafka (2 methods × 2 topics)
      result should have size 5
      val kafkaResults = result.filter(_.resourceType == "kafka")
      kafkaResults should have size 4
      kafkaResults.map(_.target).toSet shouldBe Set("topic.a", "topic.b")
      kafkaResults.foreach(_.scanner shouldBe "manual")
      // database integration untouched
      val dbResults = result.filter(_.resourceType == "database")
      dbResults should have size 1
      dbResults.head.target shouldBe "users"
    }

    "addCall creates synthetic methods if needed" in {
      val adj = LineageAdjustments.builder
        .method("pkg", "A", "handle").calls("pkg", "B", "process")
        .build

      val (methods, _) = adj.apply(Nil, Nil)
      methods should have size 2
      methods.find(_.ref == MethodRef("pkg", "A", "handle")).get.calls should contain(MethodRef("pkg", "B", "process"))
      methods.find(_.ref == MethodRef("pkg", "B", "process")) shouldBe defined
    }

    "removeCall removes call edge" in {
      val methods = List(
        ExtractedMethod("A", "pkg", "handle", List(MethodRef("pkg", "B", "process"))),
        ExtractedMethod("B", "pkg", "process", Nil),
      )

      val adj = LineageAdjustments.builder
        .method("pkg", "A", "handle").removesCall("pkg", "B", "process")
        .build

      val (result, _) = adj.apply(methods, Nil)
      result.find(_.ref == MethodRef("pkg", "A", "handle")).get.calls shouldBe empty
    }

    "renameResource renames across all integrations" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "m"), DataAccessType.Write, "kafka", "pekko-kafka", "old-topic", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "B", "n"), DataAccessType.Write, "kafka", "pekko-kafka", "old-topic", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "C", "o"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )

      val adj = LineageAdjustments.builder
        .resource("kafka", "old-topic").renameTo("new-topic")
        .build

      val (_, result) = adj.apply(Nil, existing)
      result.filter(_.resourceType == "kafka").foreach(_.target shouldBe "new-topic")
      result.find(_.resourceType == "database").get.target shouldBe "users"
    }

    "empty adjustments passes through unchanged" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "b"), DataAccessType.Read, "database", "doobie", "users", "SELECT"),
      )

      val (_, result) = LineageAdjustments.empty.apply(Nil, existing)
      result shouldBe existing
    }

    "PekkoKafkaScanner + adjustments override" in {
      val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
      val kafkaDetected = new TastyPekkoKafkaScanner().scan(List(pekkoPkg))

      val adj = LineageAdjustments.builder
        .cls[KafkaFlexiFlowProducer].removeIntegrations("kafka")
        .cls[KafkaFlexiFlowProducer].writes.kafka("events.flexiflow-topic")
        .cls[KafkaPlainSinkProducer].removeIntegrations("kafka")
        .cls[KafkaPlainSinkProducer].writes.kafka("events.plainsink-topic")
        .build

      val (_, result) = adj.apply(Nil, kafkaDetected)
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
