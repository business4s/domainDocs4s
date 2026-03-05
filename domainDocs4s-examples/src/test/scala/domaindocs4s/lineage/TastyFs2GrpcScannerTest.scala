package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyFs2GrpcScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  private val grpcIntegrations = new TastyFs2GrpcScanner().scan(List(pkg))

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
        di.resourceType shouldBe ResourceType.Grpc
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

}
