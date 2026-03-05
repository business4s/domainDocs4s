package domaindocs4s.architecture.lineage.example

import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

// ============================================================================
// Example classes with real AWS SDK v2 S3 usage for TASTy scanning.
//
// S3Exporter — writes to S3 via putObject (Write)
// S3Reader   — reads from S3 via getObject (Read)
//
// The TastyS3Scanner detects S3Client field types and method calls.
// Bucket names are not extracted (they come from config); use LineageAdjustments
// with .s3("bucket-name") to specify bucket targets.
// ============================================================================

/** Writes data to S3 — detected as Write by TastyS3Scanner. */
class S3Exporter(val s3Client: S3Client) {

  def exportData(key: String, data: String): PutObjectResponse =
    s3Client.putObject(
      PutObjectRequest.builder().bucket("my-bucket").key(key).build(),
      RequestBody.fromString(data),
    )
}

/** Reads data from S3 — detected as Read by TastyS3Scanner. */
class S3Reader(val s3Client: S3Client) {

  def readData(key: String): ResponseInputStream[GetObjectResponse] =
    s3Client.getObject(
      GetObjectRequest.builder().bucket("my-bucket").key(key).build(),
    )
}
