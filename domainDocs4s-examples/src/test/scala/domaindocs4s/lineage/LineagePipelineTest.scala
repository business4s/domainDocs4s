package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.example.{EventPublisher, UserRepo}
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class LineagePipelineTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"
  private val ph  = f"${pkg.hashCode.abs}%08x".take(8)

  private val callGraph = new TastyCallGraphExtractor().extract(pkg)
  private val doobieIntegrations = new TastyDoobieScanner().scan(List(pkg))
  private val grpcIntegrations = new TastyFs2GrpcScanner().scan(List(pkg))

  private val enrichment = IntegrationGroupConfig.builder
    .group[UserRepo]("user-db")
    .build
  private val integrations = enrichment.enrich(doobieIntegrations ++ grpcIntegrations)

  private val result = LineageBuilder.build(callGraph, integrations)

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

    "ByPackage with same package produces no grouping for base-package classes" in {
      val config = ClassLevelConfig.builder
        .groupByPackage(pkg)
        .build
      val diagram = MermaidRenderer.renderClassLevel(resultWithManual, config)

      // Classes in the base package should render as standalone nodes (not inside subgraphs)
      val lines = diagram.split("\n")
      val standaloneClassLines = lines.filter(l => l.trim.startsWith(s"cls_${ph}_"))
      standaloneClassLines should not be empty
      // Verify none of them are inside a subgraph block
      standaloneClassLines.foreach { line =>
        val idx = lines.indexOf(line)
        val precedingSubgraph = lines.take(idx).lastIndexWhere(_.contains("subgraph pkg_"))
        val precedingEnd = lines.take(idx).lastIndexWhere(_.trim == "end")
        // Either no subgraph before, or the subgraph was closed before this line
        (precedingSubgraph == -1 || precedingEnd > precedingSubgraph) shouldBe true
      }
      diagram should include(s"""cls_${ph}_UserGrpcApi["UserGrpcApi"]""")
    }
  }

  "LineageBuilder" - {

    "propagates effective access types" in {
      // UserGrpcApi.getBalance: grpc Write (server) + doobie Read (transitive) -> ReadWrite
      val apiGetBalance = result.findMethod(MethodRef(pkg, "UserGrpcApi", "getBalance"))
      apiGetBalance.map(_.effectiveAccess) shouldBe Some(DataAccessType.ReadWrite)

      // UserGrpcApi.deposit: grpc Write (server) + grpc Read (client) + doobie Write (transitive) -> ReadWrite
      val apiDeposit = result.findMethod(MethodRef(pkg, "UserGrpcApi", "deposit"))
      apiDeposit.map(_.effectiveAccess) shouldBe Some(DataAccessType.ReadWrite)
    }

    "builds lineage chains from API to DB and gRPC" in {
      result.lineageChains should not be empty

      // Filter chains that pass through UserGrpcApi
      val apiChains = result.lineageForClass("UserGrpcApi")

      // getBalance: 1 doobie + 1 grpc server = 2
      val balanceChains = apiChains.filter(_.path.exists(r => r.className == "UserGrpcApi" && r.methodName == "getBalance"))
      balanceChains should have size 2

      val balanceDoobieChains = balanceChains.filter(_.integration.scanner == "doobie")
      balanceDoobieChains should have size 1
      balanceDoobieChains.head.integration.target shouldBe "users"
      balanceDoobieChains.head.integration.accessType shouldBe DataAccessType.Read
      balanceDoobieChains.head.path.map(_.className) should contain inOrder ("UserGrpcApi", "UserService", "UserRepo")

      val balanceGrpcChains = balanceChains.filter(_.integration.scanner == "grpc")
      balanceGrpcChains should have size 1
      balanceGrpcChains.head.integration.target shouldBe "UserService/getBalance"

      // deposit: 2 doobie + 1 grpc server + 1 grpc client = 4
      val depositChains = apiChains.filter(_.path.exists(r => r.className == "UserGrpcApi" && r.methodName == "deposit"))
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
