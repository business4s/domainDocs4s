package domaindocs4s.architecture.lineage

// ============================================================================
// Lineage Builder — Phase 2
//
// Generic: works with output from ANY scanner (doobie, kafka, grpc, ...).
//
// Takes:
//   - ExtractedMethod list (call graph from TASTy extraction)
//   - DiscoveredIntegration list (from one or more scanners)
//
// Produces:
//   - ScanResult with all classes, propagated access types, and lineage chains
//
// The propagation logic:
//   1. Methods with direct integrations get their access type from the scanner
//   2. Methods that call other methods inherit (combine) their callees' access
//   3. This propagates recursively up the call chain
//   4. Lineage chains trace from entry points (no callers) to integrations
// ============================================================================

object LineageBuilder {

  def build(
      methods: List[ExtractedMethod],
      integrations: List[DiscoveredIntegration],
  ): ScanResult = {
    val integrationsByMethod = integrations.groupBy(_.method)

    // Build call graph edges
    val callGraph = methods.flatMap { m =>
      m.calls.map(callee => CallEdge(m.ref, callee))
    }

    // Direct access: only from scanner-discovered integrations
    val directAccess: Map[MethodRef, DataAccessType] = methods.map { m =>
      val ops = integrationsByMethod.getOrElse(m.ref, Nil)
      m.ref -> DataAccessType.combineAll(ops.map(_.accessType))
    }.toMap

    // Adjacency list
    val callees: Map[MethodRef, List[MethodRef]] = methods.map(m => m.ref -> m.calls).toMap

    // Propagate effective access recursively
    val effectiveAccess = propagateAccess(directAccess, callees)

    // Build ScannedMethods and ScannedClasses
    val scannedMethods = methods.map { m =>
      ScannedMethod(
        ref = m.ref,
        directAccess = directAccess.getOrElse(m.ref, DataAccessType.Pure),
        effectiveAccess = effectiveAccess.getOrElse(m.ref, DataAccessType.Pure),
        calls = m.calls,
        integrations = integrationsByMethod.getOrElse(m.ref, Nil),
      )
    }

    val scannedMethodsByClass = scannedMethods.groupBy(m => (m.ref.packageName, m.ref.className))
    val scannedClasses        = methods
      .groupBy(m => (m.packageName, m.className))
      .map { case ((pkg, className), _) =>
        ScannedClass(
          name = className,
          packageName = pkg,
          methods = scannedMethodsByClass.getOrElse((pkg, className), Nil),
        )
      }
      .toList
      .sortBy(_.name)

    // Build lineage chains
    val chains = buildLineageChains(callees, integrationsByMethod)

    ScanResult(
      classes = scannedClasses,
      callGraph = callGraph,
      integrations = integrations,
      lineageChains = chains,
    )
  }

  private def propagateAccess(
      directAccess: Map[MethodRef, DataAccessType],
      callees: Map[MethodRef, List[MethodRef]],
  ): Map[MethodRef, DataAccessType] = {
    val memo     = scala.collection.mutable.Map.empty[MethodRef, DataAccessType]
    val visiting = scala.collection.mutable.Set.empty[MethodRef]

    def resolve(ref: MethodRef): DataAccessType = {
      memo.getOrElseUpdate(
        ref, {
          if (visiting.contains(ref)) DataAccessType.Pure
          else {
            val _          = visiting.add(ref)
            val direct     = directAccess.getOrElse(ref, DataAccessType.Pure)
            val transitive = callees.getOrElse(ref, Nil).map(resolve)
            val result     = DataAccessType.combineAll(direct :: transitive)
            val _          = visiting.remove(ref)
            result
          }
        },
      )
    }

    (directAccess.keys ++ callees.keys).foreach(resolve)
    memo.toMap
  }

  private def buildLineageChains(
      callees: Map[MethodRef, List[MethodRef]],
      integrationsByMethod: Map[MethodRef, List[DiscoveredIntegration]],
  ): List[LineageChain] = {
    val allRefs     = callees.keySet
    val hasCallers  = callees.values.flatten.toSet
    val entryPoints = allRefs -- hasCallers

    def walk(
        current: MethodRef,
        path: List[MethodRef],
        visited: Set[MethodRef],
        entryPoint: MethodRef,
    ): List[LineageChain] = {
      if (visited.contains(current)) return Nil
      val currentPath = path :+ current

      val directChains = integrationsByMethod.getOrElse(current, Nil).map { integration =>
        LineageChain(entryPoint = entryPoint, path = currentPath, integration = integration)
      }

      val transitiveChains = callees.getOrElse(current, Nil).flatMap { callee =>
        walk(callee, currentPath, visited + current, entryPoint)
      }

      directChains ++ transitiveChains
    }

    entryPoints.toList
      .sortBy(r => (r.className, r.methodName))
      .flatMap(entry => walk(entry, Nil, Set.empty, entry))
  }
}
