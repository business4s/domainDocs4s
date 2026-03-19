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

      val keys = serverIntegrations.map(_.resourceId.key).toSet
      keys should contain("grpc:service=UserService/method=getBalance")
      keys should contain("grpc:service=UserService/method=deposit")
      keys should contain("grpc:service=UserService/method=getHistory")
    }

    "detects client usages" in {
      val clientIntegrations = grpcIntegrations.filter(_.accessType == DataAccessType.Read)
      clientIntegrations should not be empty

      val keys = clientIntegrations.map(_.resourceId.key).toSet
      keys should contain("grpc:service=RateService/method=getRate")
    }

    "server integrations are Write, client integrations are Read" in {
      val server = grpcIntegrations.filter(_.resourceId match { case g: ResourceId.GrpcEndpoint => g.service == "UserService"; case _ => false })
      server.foreach(_.accessType shouldBe DataAccessType.Write)

      val client = grpcIntegrations.filter(_.resourceId match { case g: ResourceId.GrpcEndpoint => g.service == "RateService"; case _ => false })
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
      depositClientCalls.head.resourceId shouldBe ResourceId.GrpcEndpoint("RateService", "getRate")
    }

    "gRPC integrations have service segment matching service name" in {
      val userServiceIntegrations = grpcIntegrations.filter(_.resourceId match { case g: ResourceId.GrpcEndpoint => g.service == "UserService"; case _ => false })
      userServiceIntegrations should not be empty
      userServiceIntegrations.foreach(_.resourceId.segments should contain(("service", "UserService")))

      val rateServiceIntegrations = grpcIntegrations.filter(_.resourceId match { case g: ResourceId.GrpcEndpoint => g.service == "RateService"; case _ => false })
      rateServiceIntegrations should not be empty
      rateServiceIntegrations.foreach(_.resourceId.segments should contain(("service", "RateService")))
    }
  }

}
