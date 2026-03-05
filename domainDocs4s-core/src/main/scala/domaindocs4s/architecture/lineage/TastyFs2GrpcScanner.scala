package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context

// ============================================================================
// TASTy-based fs2-grpc Scanner
//
// Scans compiled Scala code via TASTy to find gRPC integrations.
// Output: "classA.methodB exposes/consumes ServiceC/rpcD"
//
// Server detection (Write): class extends *Fs2Grpc trait
//   → each implemented RPC method is a gRPC endpoint exposure
//
// Client detection (Read): val field of type *Fs2Grpc
//   → each call to field.rpcMethod(...) is a gRPC client consumption
// ============================================================================

class TastyFs2GrpcScanner(using ctx: Context) extends DeclarativeScanner(
  name = "grpc",
  resourceType = ResourceType.Grpc,
  rules = Seq(
    // Server: class extends *Fs2Grpc → Write, emit per RPC method
    DetectionRule.ClassExtends(
      parentType = TypeMatcher.fqnEndsWith("Fs2Grpc"),
      accessType = DataAccessType.Write,
      emitPerMethod = true,
      targetNaming = TargetNaming.FromTypeName(stripSuffix = "Fs2Grpc", includeMethod = true),
      groupNaming = GroupNaming.FromTypeName(stripSuffix = "Fs2Grpc"),
    ),
    // Client: field type *Fs2Grpc → Read, emit per call
    DetectionRule.FieldMethodCall(
      fieldType = TypeMatcher.fqnEndsWith("Fs2Grpc"),
      methods = MethodMapping.AnyMethod(DataAccessType.Read),
      targetNaming = TargetNaming.FromTypeName(stripSuffix = "Fs2Grpc", includeMethod = true),
      groupNaming = GroupNaming.FromTypeName(stripSuffix = "Fs2Grpc"),
    ),
  ),
)
