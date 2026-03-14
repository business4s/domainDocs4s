package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

// ============================================================================
// TASTy-based S3 Scanner
//
// Scans compiled Scala code via TASTy to find AWS SDK v2 S3 client usage.
// Output: "classA.methodB reads/writes s3"
//
// Detection: fields/vals whose type is S3Client or S3AsyncClient.
// When a method body calls field.putObject(...) etc., emit Write.
// When a method body calls field.getObject(...) etc., emit Read.
//
// Bucket names come from config, not code — the scanner produces a generic
// target "S3". Use LineageAdjustments with .s3("bucket-name") to specify buckets.
// ============================================================================

class TastyS3Scanner(
    group: Option[String] = Some("S3"),
)(using ctx: Context)
    extends IntegrationScanner {

  private val writeMethods = Set("putObject", "uploadPart", "copyObject", "deleteObject", "deleteObjects")
  private val readMethods  = Set("getObject", "getObjectAsBytes", "headObject", "listObjects", "listObjectsV2")

  private val search = SymbolSearch.MethodCall(
    TypeMatcher.oneOf(
      "software.amazon.awssdk.services.s3.S3Client",
      "software.amazon.awssdk.services.s3.S3AsyncClient",
    ),
  )

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(search))
    finder.findAll(packages).collect { case u: FoundUsage.MethodCallResult => u }.flatMap { u =>
      val accessType =
        if (writeMethods.contains(u.methodName)) Some(DataAccessType.Write)
        else if (readMethods.contains(u.methodName)) Some(DataAccessType.Read)
        else None
      val ref        = u.path.toMethodRef
      accessType.map { at =>
        DiscoveredIntegration(
          method = ref,
          accessType = at,
          resourceType = ResourceType.S3,
          scanner = "s3",
          target = "S3",
          evidence = s"calls ${u.receiverName}.${u.methodName}",
          group = group,
        )
      }
    }
  }
}
