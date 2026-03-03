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
  private val doobieIntegrations = new TastyDoobieScanner().scan(pkg)
  private val grpcIntegrations = new TastyFs2GrpcScanner().scan(pkg)

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
        .build

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
        .build

      manual.head.group shouldBe Some("Kafka")
    }

    "supports custom cluster name" in {
      val manual = ManualScanner.builder
        .method[UserRepo](_.getBalance).reads.kafka("analytics.events", cluster = "Analytics")
        .build

      manual.head.group shouldBe Some("Analytics")
    }

    "supports generic custom integration type" in {
      val manual = ManualScanner.builder
        .method[UserRepo](_.getBalance).writes.custom("s3", "my-bucket/exports", group = Some("S3"))
        .build

      val di = manual.head
      di.integrationType shouldBe "s3"
      di.target shouldBe "my-bucket/exports"
      di.group shouldBe Some("S3")
    }

    "composes with automatic scanner results in LineageBuilder" in {
      val manualIntegrations = ManualScanner.builder
        .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events")
        .build

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
        .build

      manual should have size 3
      manual.count(_.integrationType == "kafka") shouldBe 2
      manual.count(_.integrationType == "audit") shouldBe 1
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
