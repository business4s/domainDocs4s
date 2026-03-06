package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.LineageAdjustment
import domaindocs4s.architecture.lineage.example.{EventPublisher, S3Exporter, UserGrpcApi, UserRepo, UserService}
import domaindocs4s.architecture.lineage.example.pekko.{KafkaFlexiFlowProducer, KafkaPlainSinkProducer}
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class LineageAdjustmentsTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  private val callGraph = new TastyCallGraphExtractor().extract(pkg)
  private val doobieIntegrations = new TastyDoobieScanner().scan(List(pkg))
  private val grpcIntegrations = new TastyFs2GrpcScanner().scan(List(pkg))

  private val enrichment = IntegrationGroupConfig.builder
    .group[UserRepo]("user-db")
    .build

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
      e.resourceType shouldBe ResourceType.Kafka
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
        .method[UserRepo](_.getBalance).reads.custom(ResourceType.Kafka, "analytics.events", group = Some("Analytics"))
        .build

      val (_, result) = adj.apply(Nil, Nil)
      result.head.group shouldBe Some("Analytics")
    }

    "supports generic custom resource type" in {
      val adj = LineageAdjustments.builder
        .method[UserRepo](_.getBalance).writes.custom(ResourceType.S3, "my-bucket/exports", group = Some("S3"))
        .build

      val (_, result) = adj.apply(Nil, Nil)
      val e = result.head
      e.resourceType shouldBe ResourceType.S3
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
      e.resourceType shouldBe ResourceType.S3
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

      val depositChains = resultWithManual.lineageForClass("UserGrpcApi")
        .filter(_.path.exists(r => r.className == "UserGrpcApi" && r.methodName == "deposit"))
      val kafkaChains = depositChains.filter(_.integration.resourceType == ResourceType.Kafka)
      kafkaChains should have size 1
      kafkaChains.head.integration.target shouldBe "user.deposit-events"
      kafkaChains.head.integration.accessType shouldBe DataAccessType.Write
      kafkaChains.head.path.map(_.className) should contain inOrder ("UserGrpcApi", "EventPublisher")
    }

    "supports multiple adjustments in a single builder" in {
      val adj = LineageAdjustments.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .method[UserGrpcApi](_.getHistory).reads.custom(ResourceType.Kafka, "user.history-events", group = Some("Analytics"))
        .method[UserService](_.deposit).writes.custom(ResourceType("audit"), "audit-log", group = Some("Audit"))
        .build

      val (_, result) = adj.apply(Nil, Nil)
      result should have size 3
      result.count(_.resourceType == ResourceType.Kafka) shouldBe 2
      result.count(_.resourceType == ResourceType("audit")) shouldBe 1
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
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
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
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
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
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "updateBalance"), DataAccessType.Write, ResourceType.Database, "doobie", "users", "UPDATE"),
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
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
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
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "getBalance"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
        DiscoveredIntegration(MethodRef(pkg, "UserRepo", "updateBalance"), DataAccessType.Write, ResourceType.Database, "doobie", "users", "UPDATE"),
      )
      val (resultMethods, resultIntegrations) = adj.apply(methods, existingIntegrations)

      resultMethods.map(_.className) should not contain "UserRepo"
      resultMethods.head.calls shouldBe empty
      resultIntegrations shouldBe empty
    }

    "resource().renameTo renames target across integrations" in {
      val adj = LineageAdjustments.builder
        .resource(ResourceType.Kafka, "old-topic").renameTo("new-topic")
        .build

      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "m"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "old-topic", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "B", "n"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "other-topic", "evidence", Some("Kafka")),
      )
      val (_, result) = adj.apply(Nil, existing)
      result.find(_.method.className == "A").get.target shouldBe "new-topic"
      result.find(_.method.className == "B").get.target shouldBe "other-topic"
    }

    "resource().remove removes all integrations to target" in {
      val adj = LineageAdjustments.builder
        .resource(ResourceType.Kafka, "old-topic").remove
        .build

      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "m"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "old-topic", "evidence"),
        DiscoveredIntegration(MethodRef("", "B", "n"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
      )
      val (_, result) = adj.apply(Nil, existing)
      result should have size 1
      result.head.target shouldBe "users"
    }

    "resource().setGroup changes group" in {
      val adj = LineageAdjustments.builder
        .resource(ResourceType.Database, "users").setGroup("user-db")
        .build

      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "m"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
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
      result(0).resourceType shouldBe ResourceType.Database
      result(0).group shouldBe Some("user-db")
      result(1).resourceType shouldBe ResourceType.Grpc
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
        DiscoveredIntegration(MethodRef("", "Handler", "send"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "auto-topic", "evidence"),
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
        DiscoveredIntegration(MethodRef("", "LowLevel", "write"), DataAccessType.Write, ResourceType.S3, "s3", "S3", "putObject"),
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
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
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
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
      )
      val methods = List(
        ExtractedMethod("Handler", "", "query", Nil),
      )

      // kafka is not auto-detected, but .undetected(ResourceType.Kafka) marks it as manual-only
      val adj = LineageAdjustments.builder
        .undetected(ResourceType.Kafka)
        .cls("", "Handler").writes.kafka("topic")
        .cls("", "Handler").reads.database("users")
        .build

      // Should not throw — kafka is manual-only, database is detected
      val (_, result) = adj.apply(methods, existing)
      result.count(_.scanner == "manual") shouldBe 2
    }

    ".undetected on integration builder opts out for current entries only" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
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
        DiscoveredIntegration(MethodRef("", "Handler", "send"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "auto-topic", "evidence"),
      )
      val methods = List(
        ExtractedMethod("Handler", "", "send", Nil),
      )

      // Builder marks kafka as undetected, but .detected overrides for this entry
      val adj = LineageAdjustments.builder
        .undetected(ResourceType.Kafka)
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
        .undetected(ResourceType.Kafka)
        .cls("", "Handler").writes.kafka("topic").detected
        .build

      val ex = intercept[IllegalStateException] {
        adj.apply(methods, Nil)
      }
      ex.getMessage should include("kafka")
    }
  }

  "LineageAdjustments apply" - {

    "addIntegration always adds to integration list" in {
      val existing = List(
        DiscoveredIntegration(
          method = MethodRef("", "MyProducer", "send"),
          accessType = DataAccessType.Write,
          resourceType = ResourceType.Kafka,
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
        DiscoveredIntegration(MethodRef("", "MyProducer", "send"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "topic-a", "evidence"),
        DiscoveredIntegration(MethodRef("", "MyProducer", "send"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "topic-b", "evidence"),
      )

      val adj = LineageAdjustments.builder
        .method("", "MyProducer", "send").removeIntegration(ResourceType.Kafka, "topic-a")
        .build

      val (_, result) = adj.apply(Nil, existing)
      result should have size 1
      result.head.target shouldBe "topic-b"
    }

    "removeIntegrationsByType removes all of a type" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "m"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "topic-a", "evidence"),
        DiscoveredIntegration(MethodRef("", "Handler", "m"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "topic-b", "evidence"),
        DiscoveredIntegration(MethodRef("", "Handler", "m"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
      )

      val adj = LineageAdjustments.builder
        .method("", "Handler", "m").removeIntegrations(ResourceType.Kafka)
        .build

      val (_, result) = adj.apply(Nil, existing)
      result should have size 1
      result.head.resourceType shouldBe ResourceType.Database
    }

    "addClassIntegration adds to methods with matching resourceType" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "Handler", "methodA"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "unknown-a", "evidence"),
        DiscoveredIntegration(MethodRef("", "Handler", "methodB"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "unknown-b", "evidence"),
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
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
        DiscoveredIntegration(MethodRef("", "Handler", "methodA"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "unknown-a", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "Handler", "methodB"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "unknown-b", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "Handler", "query"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
      )

      val adj = LineageAdjustments.builder
        .cls("", "Handler").removeIntegrations(ResourceType.Kafka)
        .cls("", "Handler").writes.kafka("topic.a").kafka("topic.b")
        .build

      val (_, result) = adj.apply(Nil, existing)
      // 1 database + 4 kafka (2 methods x 2 topics)
      result should have size 5
      val kafkaResults = result.filter(_.resourceType == ResourceType.Kafka)
      kafkaResults should have size 4
      kafkaResults.map(_.target).toSet shouldBe Set("topic.a", "topic.b")
      kafkaResults.foreach(_.scanner shouldBe "manual")
      // database integration untouched
      val dbResults = result.filter(_.resourceType == ResourceType.Database)
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
        DiscoveredIntegration(MethodRef("", "A", "m"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "old-topic", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "B", "n"), DataAccessType.Write, ResourceType.Kafka, "pekko-kafka", "old-topic", "evidence", Some("Kafka")),
        DiscoveredIntegration(MethodRef("", "C", "o"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
      )

      val adj = LineageAdjustments.builder
        .resource(ResourceType.Kafka, "old-topic").renameTo("new-topic")
        .build

      val (_, result) = adj.apply(Nil, existing)
      result.filter(_.resourceType == ResourceType.Kafka).foreach(_.target shouldBe "new-topic")
      result.find(_.resourceType == ResourceType.Database).get.target shouldBe "users"
    }

    "empty adjustments passes through unchanged" in {
      val existing = List(
        DiscoveredIntegration(MethodRef("", "A", "b"), DataAccessType.Read, ResourceType.Database, "doobie", "users", "SELECT"),
      )

      val (_, result) = LineageAdjustments.empty.apply(Nil, existing)
      result shouldBe existing
    }

    "PekkoKafkaScanner + adjustments override" in {
      val pekkoPkg = "domaindocs4s.architecture.lineage.example.pekko"
      val kafkaDetected = new TastyPekkoKafkaScanner().scan(List(pekkoPkg))

      val adj = LineageAdjustments.builder
        .cls[KafkaFlexiFlowProducer].removeIntegrations(ResourceType.Kafka)
        .cls[KafkaFlexiFlowProducer].writes.kafka("events.flexiflow-topic")
        .cls[KafkaPlainSinkProducer].removeIntegrations(ResourceType.Kafka)
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

}
