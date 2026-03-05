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
)(using ctx: Context) extends DeclarativeScanner(
  name = "s3",
  resourceType = ResourceType.S3,
  rules = Seq(
    DetectionRule.FieldMethodCall(
      fieldType = TypeMatcher.oneOf(
        "software.amazon.awssdk.services.s3.S3Client",
        "software.amazon.awssdk.services.s3.S3AsyncClient",
      ),
      methods = MethodMapping.Named(
        writeMethods = Set("putObject", "uploadPart", "copyObject", "deleteObject", "deleteObjects"),
        readMethods = Set("getObject", "getObjectAsBytes", "headObject", "listObjects", "listObjectsV2"),
      ),
    ),
  ),
  defaultTarget = "S3",
  group = group,
)
