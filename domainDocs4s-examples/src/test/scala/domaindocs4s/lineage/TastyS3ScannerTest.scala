package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.architecture.lineage.example.S3Exporter
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TastyS3ScannerTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  private val s3Integrations = new TastyS3Scanner().scan(List(pkg))

  "TastyS3Scanner" - {

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

    "detects S3 putObject inside if/else branches" in {
      val writes = s3Integrations.filter { di =>
        di.method.className == "S3ConditionalExporter" && di.accessType == DataAccessType.Write
      }
      writes should have size 1
      writes.head.target shouldBe "S3"
      writes.head.evidence should include("putObject")
    }

    "detects S3 putObject via lambda parameter (not class field)" in {
      val writes = s3Integrations.filter { di =>
        di.method.className == "S3LambdaExporter" && di.accessType == DataAccessType.Write
      }
      writes should have size 1
      writes.head.target shouldBe "S3"
      writes.head.evidence should include("putObject")
    }

    "all S3 integrations have resourceType s3 and scanner s3" in {
      s3Integrations should not be empty
      s3Integrations.foreach { di =>
        di.resourceType shouldBe ResourceType.S3
        di.scanner shouldBe "s3"
      }
    }

    "all S3 integrations have group S3" in {
      s3Integrations.foreach(_.group shouldBe Some("S3"))
    }

    "LineageAdjustments .s3(bucket) overrides auto-detected S3 targets" in {
      val adj = LineageAdjustments.builder
        .cls[S3Exporter].removeIntegrations(ResourceType.S3)
        .cls[S3Exporter].writes.s3("ledger-exports/assets")
        .build

      val (_, result) = adj.apply(Nil, s3Integrations)
      val exporterResults = result.filter(_.method.className == "S3Exporter")
      exporterResults should have size 1
      exporterResults.head.target shouldBe "ledger-exports/assets"
      exporterResults.head.scanner shouldBe "manual"
      exporterResults.head.resourceType shouldBe ResourceType.S3
      exporterResults.head.group shouldBe Some("S3")

      // S3Reader integrations should be untouched
      val readerResults = result.filter(_.method.className == "S3Reader")
      readerResults should have size 1
      readerResults.head.target shouldBe "S3"
      readerResults.head.scanner shouldBe "s3"
    }
  }

}
