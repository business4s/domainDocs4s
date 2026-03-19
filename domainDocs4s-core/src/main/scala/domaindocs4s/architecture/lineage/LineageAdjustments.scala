package domaindocs4s.architecture.lineage

import domaindocs4s.macros.MethodRefMacro
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag

// ============================================================================
// Lineage Adjustments — the ultimate escape hatch
//
// Selector-based API for arbitrarily modifying auto-detected lineage graphs.
// Replaces ManualScanner with full graph manipulation capabilities.
//
// Selectors pinpoint an element:
//   method[T](_.name)              → a method in a class (compile-time checked)
//   method(pkg, cls, name)         → a method (string-based, for non-TASTy elements)
//   cls[T]                         → all methods in a class
//   cls(pkg, name)                 → all methods in a class (string-based)
//   resource(resourceType, target) → an external resource node
//
// Actions per selector (Method and Class support the same operations):
//   Method/Class: .reads/.writes + .kafka/.s3/.database/.grpc/.journal/.custom
//                 .calls[T](_.name)  .removesCall[T](_.name)
//                 .removeIntegration(type, target)  .removeIntegrations(type)
//                 .remove  (hide: reconnect callers → callees, promote integrations)
//                 .delete  (hard removal: disconnects the graph)
//   Class only:   .show  (allowlist: when any .show exists, non-shown classes are hidden)
//                 .renameTo(displayName)
//                 .reads/.writes  — detected by default (fail if scanner didn't find this type)
//                 .reads/.writes...undetected  (this is manual-only, don't expect scanner output)
//                 .undetected("s3", ...)       (builder-level: these types are manual-only)
//   Resource:     .renameTo(newTarget)  .setGroup(group)  .remove
//
// Usage:
//   val adj = LineageAdjustments.builder
//     .method[EventPublisher](_.publish).writes.kafka("events.topic")
//     .method[UserService](_.deposit).calls[AuditService](_.log)
//     .cls[S3Handler].removeIntegrations("s3")
//     .method[S3Handler](_.export).writes.s3("exports-bucket")
//     .resource("kafka", "old-topic").renameTo("new-topic")
//     .cls[InternalHelper].remove
//     .build
//
// Override pattern (replace auto-detected targets):
//   .cls[KafkaProducer].removeIntegrations("kafka")
//   .cls[KafkaProducer].writes.kafka("real-topic")
// ============================================================================

/** A single adjustment to the lineage graph. */
sealed trait LineageAdjustment

object LineageAdjustment {

  // ── Integration edges (method → external resource) ──────────────────────

  /** Add an integration from a specific method to an external resource. */
  case class AddIntegration(
      method: MethodRef,
      accessType: DataAccessType,
      resourceId: ResourceId,
  ) extends LineageAdjustment

  /** Add an integration at class level — resolves to matching methods at apply time.
    *
    * Resolution: finds methods with existing integrations of the same resourceType (from original auto-detected integrations). If none match, uses
    * first method found in the class. If class has no methods, creates a synthetic one.
    *
    * When `expectDetected = true` (the default), apply() will throw if no auto-detected integration of the same resourceType exists for this class or
    * any method reachable through the call graph. Use `.undetected` on the integration builder or `Builder.undetected("s3", ...)` to mark entries as
    * manual-only.
    */
  case class AddClassIntegration(
      packageName: String,
      className: String,
      accessType: DataAccessType,
      resourceId: ResourceId,
      expectDetected: Boolean = true,
  ) extends LineageAdjustment

  /** Remove a specific integration by (class [+ method], resourceType, target label). */
  case class RemoveIntegration(
      packageName: String,
      className: String,
      methodName: Option[String],
      resourceType: ResourceType,
      target: String,
  ) extends LineageAdjustment

  /** Remove all integrations of a given resourceType from a class [+ method]. */
  case class RemoveIntegrationsByType(
      packageName: String,
      className: String,
      methodName: Option[String],
      resourceType: ResourceType,
  ) extends LineageAdjustment

  /** Override the ResourceId of all integrations matching (class [+ method], resourceType). Replaces unresolved auto-detected resources with a
    * concrete one.
    */
  case class OverrideResource(
      packageName: String,
      className: String,
      methodName: Option[String],
      resourceType: ResourceType,
      newResourceId: ResourceId,
  ) extends LineageAdjustment

  // ── Call graph edges (method → method) ──────────────────────────────────

  /** Add a call edge from one method to another. Creates synthetic ExtractedMethod entries if either doesn't exist.
    */
  case class AddCall(from: MethodRef, to: MethodRef) extends LineageAdjustment

  /** Remove a call edge between two methods. */
  case class RemoveCall(from: MethodRef, to: MethodRef) extends LineageAdjustment

  /** Add a call edge from a class to a method — resolves the "from" method at apply time.
    *
    * Resolution: finds methods in the class that already call any method in the target's class. If none match, uses the first method in the class. If
    * the class has no methods, creates a synthetic one.
    */
  case class AddClassCall(fromPackage: String, fromClass: String, to: MethodRef) extends LineageAdjustment

  /** Remove all call edges from methods in a class to a target method. */
  case class RemoveClassCall(fromPackage: String, fromClass: String, to: MethodRef) extends LineageAdjustment

  // ── Node modifications ──────────────────────────────────────────────────

  /** Hide a method: remove it but reconnect callers → callees and promote integrations to callers. */
  case class HideMethod(ref: MethodRef) extends LineageAdjustment

  /** Hide a class: remove all its methods but reconnect callers → callees and promote integrations. */
  case class HideClass(packageName: String, className: String) extends LineageAdjustment

  /** Hard-delete a method and all its edges (call edges + integrations). Disconnects the graph. */
  case class DeleteMethod(ref: MethodRef) extends LineageAdjustment

  /** Hard-delete a class and all its methods and edges. Disconnects the graph. */
  case class DeleteClass(packageName: String, className: String) extends LineageAdjustment

  // ── External resource modifications ─────────────────────────────────────

  /** Rename a resource's label (innermost segment) across all integrations. For structured changes use ReplaceResourceId.
    * Matches by resourceType and the current label (innermost segment value).
    */
  case class RenameResource(resourceType: ResourceType, oldTarget: String, newTarget: String) extends LineageAdjustment

  /** Replace the entire ResourceId of integrations matching (resourceType, old label). */
  case class ReplaceResourceId(resourceType: ResourceType, oldTarget: String, newResourceId: ResourceId) extends LineageAdjustment

  /** Remove all integrations pointing to a specific resource (by label). */
  case class RemoveResource(resourceType: ResourceType, target: String) extends LineageAdjustment

  /** Rename all resources whose label matches a regex pattern. Effectively merges multiple resources (e.g. partition tables) into a single node.
    */
  case class RenameResourceByPattern(resourceType: ResourceType, pattern: scala.util.matching.Regex, newTarget: String) extends LineageAdjustment

  /** Remove all integrations whose resource label matches a regex pattern. */
  case class RemoveResourceByPattern(resourceType: ResourceType, pattern: scala.util.matching.Regex) extends LineageAdjustment

  // ── Package-level modifications ─────────────────────────────────────

  /** Hide all classes whose package starts with prefix (batch HideClass with promotion). */
  case class HidePackage(packagePrefix: String) extends LineageAdjustment

  /** Set rendering group on a specific class (metadata-only, no effect on call graph). */
  case class SetClassGroup(packageName: String, className: String, group: String) extends LineageAdjustment

  /** Set rendering group on all classes in a package prefix (metadata-only, no effect on call graph). */
  case class SetPackageGroup(packagePrefix: String, group: String) extends LineageAdjustment

  // ── Display modifications ───────────────────────────────────────────

  /** Rename a class for display purposes (label only, does not change IDs or data). */
  case class RenameClass(packageName: String, className: String, displayName: String) extends LineageAdjustment

  // ── Visibility (allowlist) ─────────────────────────────────────────

  /** Explicitly show a class in the diagram. When any ShowClass adjustment exists, only shown classes are visible — everything else is hidden (with
    * promotion).
    */
  case class ShowClass(packageName: String, className: String) extends LineageAdjustment
}

/** Adjustments to apply to auto-detected lineage data before building.
  *
  * Applied between scanner output and LineageBuilder — modifies both the call graph (ExtractedMethod list) and integrations (DiscoveredIntegration
  * list), then LineageBuilder recomputes effective access types and lineage chains.
  */
case class LineageAdjustments(adjustments: List[LineageAdjustment] = Nil) {

  private def matchesPackagePrefix(pkg: String, prefix: String): Boolean =
    pkg == prefix || pkg.startsWith(prefix + ".")

  /** Replace the innermost segment (label) of a ResourceId, preserving the outer segments. */
  private def renameLabel(rid: ResourceId, newLabel: String): ResourceId = rid match {
    case r: ResourceId.DbTable      => r.copy(table = newLabel)
    case r: ResourceId.S3Object     => r.copy(path = newLabel)
    case r: ResourceId.KafkaTopic   => r.copy(topic = newLabel)
    case r: ResourceId.GrpcEndpoint => r.copy(method = newLabel)
    case r: ResourceId.Generic      => r.copy(segments = r.segments.init :+ (r.segments.last._1 -> newLabel))
  }

  /** Display name overrides for classes: (packageName, className) → displayName. */
  lazy val classRenames: Map[(String, String), String] =
    adjustments.collect { case LineageAdjustment.RenameClass(pkg, cls, name) => (pkg, cls) -> name }.toMap

  /** Classes explicitly shown via ShowClass adjustments. */
  private lazy val shownClasses: Set[(String, String)] =
    adjustments.collect { case LineageAdjustment.ShowClass(pkg, cls) => (pkg, cls) }.toSet

  /** Resolve class groups from SetClassGroup and SetPackageGroup adjustments. Requires the resolved call graph to expand package-level groups.
    * SetClassGroup takes precedence over SetPackageGroup.
    */
  def classGroups(methods: List[ExtractedMethod]): Map[(String, String), String] = {
    val packageGroups       = adjustments.collect { case LineageAdjustment.SetPackageGroup(prefix, group) => (prefix, group) }
    val classGroupOverrides = adjustments.collect { case LineageAdjustment.SetClassGroup(pkg, cls, group) => (pkg, cls) -> group }.toMap

    if (packageGroups.isEmpty && classGroupOverrides.isEmpty) return Map.empty

    val allClassKeys = methods.map(m => (m.packageName, m.className)).distinct

    val fromPackages: Map[(String, String), String] = allClassKeys.flatMap { case key @ (pkg, _) =>
      packageGroups.collectFirst {
        case (prefix, group) if matchesPackagePrefix(pkg, prefix) => key -> group
      }
    }.toMap

    fromPackages ++ classGroupOverrides // class-level overrides win
  }

  /** Extract view definitions from ShowClass adjustments.
    *
    * When any ShowClass adjustments exist, produces a "Code-defined" view where all non-shown classes are hidden. Takes the raw call graph (before
    * apply() filters it) to compute the full class set.
    */
  def extractViews(rawCallGraph: List[ExtractedMethod]): List[ViewDefinition] = {
    if (shownClasses.isEmpty) Nil
    else {
      val allClasses = rawCallGraph.map(m => (m.packageName, m.className)).distinct
      val hiddenClasses = allClasses.filterNot(shownClasses.contains).toSet
      List(ViewDefinition("Code-defined", hiddenClasses))
    }
  }

  /** Apply all adjustments to the raw scanner output.
    *
    * @param callGraph
    *   extracted methods with call edges (from TastyCallGraphExtractor)
    * @param integrations
    *   discovered integrations (from all scanners)
    * @return
    *   (methods, integrations, orphanedIntegrations) where orphanedIntegrations are integrations
    *   from hidden classes that had no non-hidden callers — they should become resource-only.
    */
  def apply(
      callGraph: List[ExtractedMethod],
      integrations: List[DiscoveredIntegration],
  ): (List[ExtractedMethod], List[DiscoveredIntegration], List[DiscoveredIntegration]) = {
    import LineageAdjustment.*

    val originalIntegrations = integrations
    var methods              = callGraph
    var integ                = integrations
    val syntheticMethods     = ListBuffer.empty[MethodRef]

    // Shared logic for HideClass and HidePackage: remove hidden methods,
    // reconnect callers → callees through the hidden set, and promote integrations.
    def hideRefs(hiddenRefs: Set[MethodRef]): Unit = if (hiddenRefs.nonEmpty) {
      // Build indexes for O(1) lookups instead of O(n) linear scans
      val methodByRef: Map[MethodRef, ExtractedMethod]     = methods.map(m => m.ref -> m).toMap
      val callersByCallee: Map[MethodRef, List[MethodRef]] = {
        val builder = scala.collection.mutable.Map.empty[MethodRef, ListBuffer[MethodRef]]
        for (m <- methods; callee <- m.calls)
          builder.getOrElseUpdate(callee, ListBuffer.empty) += m.ref
        builder.view.mapValues(_.toList).toMap
      }

      // Resolve external callees transitively through hidden methods.
      // Memoized: each hidden node is resolved once, giving O(V+E) total.
      val calleeMemo                                     = scala.collection.mutable.Map.empty[MethodRef, Set[MethodRef]]
      val calleeInProgress                               = scala.collection.mutable.Set.empty[MethodRef]
      def resolveCallees(ref: MethodRef): Set[MethodRef] = {
        if (calleeInProgress.contains(ref)) return Set.empty // cycle
        calleeMemo.getOrElse(
          ref, {
            calleeInProgress += ref
            val result = methodByRef
              .get(ref)
              .map(_.calls)
              .getOrElse(Nil)
              .flatMap { callee =>
                if (!hiddenRefs.contains(callee)) Set(callee)
                else resolveCallees(callee)
              }
              .toSet
            calleeInProgress -= ref
            calleeMemo(ref) = result
            result
          },
        )
      }

      val externalCallees: Map[MethodRef, Set[MethodRef]] =
        hiddenRefs.map(r => r -> resolveCallees(r)).toMap

      // Find non-hidden callers transitively for integration promotion.
      // Memoized: each hidden node is resolved once, giving O(V+E) total.
      val callerMemo                                           = scala.collection.mutable.Map.empty[MethodRef, Set[MethodRef]]
      val callerInProgress                                     = scala.collection.mutable.Set.empty[MethodRef]
      def findNonHiddenCallers(ref: MethodRef): Set[MethodRef] = {
        if (callerInProgress.contains(ref)) return Set.empty // cycle
        callerMemo.getOrElse(
          ref, {
            callerInProgress += ref
            val result = callersByCallee
              .getOrElse(ref, Nil)
              .flatMap { c =>
                if (!hiddenRefs.contains(c)) Set(c)
                else findNonHiddenCallers(c)
              }
              .toSet
            callerInProgress -= ref
            callerMemo(ref) = result
            result
          },
        )
      }

      // Promote integrations from hidden methods to their non-hidden callers
      val hiddenIntegrations = integ.filter(di => hiddenRefs.contains(di.method))
      val promoted           = hiddenIntegrations.flatMap { di =>
        findNonHiddenCallers(di.method).map(caller => di.copy(method = caller))
      }

      // Reconnect callers to bypass hidden nodes
      methods = methods.map { m =>
        if (hiddenRefs.contains(m.ref)) m
        else {
          val (hiddenCalls, otherCalls) = m.calls.partition(hiddenRefs.contains)
          val newCalls                  = otherCalls ++ hiddenCalls.flatMap(externalCallees.getOrElse(_, Set.empty))
          m.copy(calls = newCalls.distinct)
        }
      }

      // Remove hidden methods and their integrations, add promoted
      methods = methods.filterNot(m => hiddenRefs.contains(m.ref))
      integ = integ.filterNot(di => hiddenRefs.contains(di.method)) ++ promoted
    }

    for (adj <- adjustments) adj match {

      case AddIntegration(method, accessType, resourceId) =>
        integ = integ :+ DiscoveredIntegration(
          method = method,
          accessType = accessType,
          resourceId = resourceId,
          scanner = "manual",
          evidence = "manual adjustment",
        )
        syntheticMethods += method

      case AddClassIntegration(pkg, cls, accessType, resourceId, expectDetected) =>
        val resourceType = resourceId.resourceType
        // When expectDetected, validate that auto-detection found this resourceType on the class or its callees
        if (expectDetected) {
          // Direct: integration on a method of this class
          val directDetection =
            originalIntegrations.exists(di => di.method.packageName == pkg && di.method.className == cls && di.resourceType == resourceType)
          // Transitive: integration on a method reachable through the call graph
          val hasDetection    = directDetection || {
            val classMethodRefs                                                    = methods.filter(m => m.packageName == pkg && m.className == cls).map(_.ref).toSet
            val callMap                                                            = methods.map(m => m.ref -> m.calls).toMap
            def reachable(ref: MethodRef, visited: Set[MethodRef]): Set[MethodRef] =
              if (visited.contains(ref)) Set.empty
              else callMap.getOrElse(ref, Nil).flatMap(c => Set(c) ++ reachable(c, visited + ref)).toSet
            val transitiveRefs                                                     = classMethodRefs.flatMap(r => reachable(r, Set.empty))
            originalIntegrations.exists(di => transitiveRefs.contains(di.method) && di.resourceType == resourceType)
          }
          if (!hasDetection)
            throw new IllegalStateException(
              s"Detection check failed: no auto-detected '$resourceType' integration found for class '$cls' " +
                s"(package '$pkg') or any method reachable through its call graph. " +
                s"Ensure scanners detect this resource type, or use .undetected to mark as manual-only.",
            )
        }

        // Find methods that had this resourceType in the ORIGINAL integrations
        val matchingMethods = originalIntegrations
          .filter(di => di.method.packageName == pkg && di.method.className == cls && di.resourceType == resourceType)
          .map(_.method)
          .distinct

        val targetMethods = if (matchingMethods.nonEmpty) {
          matchingMethods
        } else {
          // Fall back: any method in the class, or create synthetic
          val classMethods = methods.filter(m => m.packageName == pkg && m.className == cls)
          if (classMethods.nonEmpty) {
            List(classMethods.head.ref)
          } else {
            val synRef = MethodRef(pkg, cls, cls)
            syntheticMethods += synRef
            List(synRef)
          }
        }

        for (m <- targetMethods)
          integ = integ :+ DiscoveredIntegration(
            method = m,
            accessType = accessType,
            resourceId = resourceId,
            scanner = "manual",
            evidence = "manual adjustment (class-level)",
          )

      case RemoveIntegration(pkg, cls, methodName, resourceType, target) =>
        integ = integ.filterNot { di =>
          di.method.packageName == pkg &&
          di.method.className == cls &&
          methodName.forall(_ == di.method.methodName) &&
          di.resourceType == resourceType &&
          di.target == target
        }

      case RemoveIntegrationsByType(pkg, cls, methodName, resourceType) =>
        integ = integ.filterNot { di =>
          di.method.packageName == pkg &&
          di.method.className == cls &&
          methodName.forall(_ == di.method.methodName) &&
          di.resourceType == resourceType
        }

      case AddCall(from, to) =>
        val idx = methods.indexWhere(_.ref == from)
        if (idx >= 0) {
          val existing = methods(idx)
          if (!existing.calls.contains(to))
            methods = methods.updated(idx, existing.copy(calls = existing.calls :+ to))
        } else {
          methods = methods :+ ExtractedMethod(from.className, from.packageName, from.methodName, List(to))
        }
        // Ensure callee exists in call graph
        if (!methods.exists(_.ref == to))
          methods = methods :+ ExtractedMethod(to.className, to.packageName, to.methodName, Nil)

      case RemoveCall(from, to) =>
        methods = methods.map { m =>
          if (m.ref == from) m.copy(calls = m.calls.filterNot(_ == to))
          else m
        }

      case AddClassCall(fromPkg, fromCls, to) =>
        // Find methods in the class that already call the target's class
        val classMethods       = methods.filter(m => m.packageName == fromPkg && m.className == fromCls)
        val callingTargetClass = classMethods.filter(_.calls.exists(c => c.packageName == to.packageName && c.className == to.className))
        val targets            =
          if (callingTargetClass.nonEmpty) callingTargetClass
          else if (classMethods.nonEmpty) List(classMethods.head)
          else {
            val synRef = MethodRef(fromPkg, fromCls, fromCls)
            syntheticMethods += synRef
            List(ExtractedMethod(fromCls, fromPkg, fromCls, Nil))
          }
        for (m <- targets) {
          val idx = methods.indexWhere(_.ref == m.ref)
          if (idx >= 0) {
            val existing = methods(idx)
            if (!existing.calls.contains(to))
              methods = methods.updated(idx, existing.copy(calls = existing.calls :+ to))
          }
        }
        // Ensure callee exists in call graph
        if (!methods.exists(_.ref == to))
          methods = methods :+ ExtractedMethod(to.className, to.packageName, to.methodName, Nil)

      case RemoveClassCall(fromPkg, fromCls, to) =>
        methods = methods.map { m =>
          if (m.packageName == fromPkg && m.className == fromCls) m.copy(calls = m.calls.filterNot(_ == to))
          else m
        }

      case HideMethod(ref) =>
        methods.find(_.ref == ref).foreach { hidden =>
          // Find callers of the hidden method
          val callerRefs   = methods.filter(m => m.ref != ref && m.calls.contains(ref)).map(_.ref)
          // Reconnect: callers get hidden method's callees
          methods = methods.map { m =>
            if (m.calls.contains(ref))
              m.copy(calls = (m.calls.filterNot(_ == ref) ++ hidden.calls).distinct)
            else m
          }
          // Promote integrations to callers
          val hiddenIntegs = integ.filter(_.method == ref)
          val promoted     = callerRefs.flatMap(caller => hiddenIntegs.map(_.copy(method = caller)))
          // Remove hidden method and its integrations, add promoted
          methods = methods.filterNot(_.ref == ref)
          integ = integ.filterNot(_.method == ref) ++ promoted
        }

      case HideClass(pkg, cls) =>
        hideRefs(methods.filter(m => m.packageName == pkg && m.className == cls).map(_.ref).toSet)

      case HidePackage(prefix) =>
        hideRefs(methods.filter(m => matchesPackagePrefix(m.packageName, prefix)).map(_.ref).toSet)

      case DeleteMethod(ref) =>
        methods = methods.collect {
          case m if m.ref != ref => m.copy(calls = m.calls.filterNot(_ == ref))
        }
        integ = integ.filterNot(_.method == ref)

      case DeleteClass(pkg, cls) =>
        val matches = (r: MethodRef) => r.packageName == pkg && r.className == cls
        methods = methods.collect {
          case m if !matches(m.ref) => m.copy(calls = m.calls.filterNot(matches))
        }
        integ = integ.filterNot(i => matches(i.method))

      case RenameResource(resourceType, oldTarget, newTarget) =>
        integ = integ.map { di =>
          if (di.resourceType == resourceType && di.target == oldTarget)
            di.copy(resourceId = renameLabel(di.resourceId, newTarget))
          else di
        }

      case ReplaceResourceId(resourceType, oldTarget, newResourceId) =>
        integ = integ.map { di =>
          if (di.resourceType == resourceType && di.target == oldTarget) di.copy(resourceId = newResourceId)
          else di
        }

      case RemoveResource(resourceType, target) =>
        integ = integ.filterNot(di => di.resourceType == resourceType && di.target == target)

      case OverrideResource(pkg, cls, methodName, resourceType, newResourceId) =>
        integ = integ.map { di =>
          if (
            di.method.packageName == pkg &&
            di.method.className == cls &&
            methodName.forall(_ == di.method.methodName) &&
            di.resourceType == resourceType
          ) di.copy(resourceId = newResourceId)
          else di
        }

      case RenameResourceByPattern(resourceType, pattern, newTarget) =>
        integ = integ.map { di =>
          if (di.resourceType == resourceType && pattern.matches(di.target))
            di.copy(resourceId = renameLabel(di.resourceId, newTarget))
          else di
        }

      case RemoveResourceByPattern(resourceType, pattern) =>
        integ = integ.filterNot(di => di.resourceType == resourceType && pattern.matches(di.target))

      case RenameClass(_, _, _)   => // display-only, handled via classRenames
      case SetClassGroup(_, _, _) => // metadata-only, handled via classGroups()
      case SetPackageGroup(_, _)  => // metadata-only, handled via classGroups()
      case ShowClass(_, _)        => // handled after main loop
    }

    // ShowClass allowlist: if any ShowClass adjustments exist, hide all non-shown classes
    var orphaned = List.empty[DiscoveredIntegration]
    if (shownClasses.nonEmpty) {
      val hiddenRefs = methods
        .filterNot(m => shownClasses.contains((m.packageName, m.className)))
        .map(_.ref)
        .toSet
      hideRefs(hiddenRefs)

      // Integrations from non-shown classes that weren't promoted to shown callers
      // become "orphaned" — they should appear as resource-only (no class connection)
      // rather than being dropped entirely. Only apply when a call graph was provided —
      // resource-only integrations (e.g., flyway) are processed with an empty call graph.
      if (callGraph.nonEmpty) {
        val (shown, notShown) = integ.partition(di => shownClasses.contains((di.method.packageName, di.method.className)))
        orphaned = notShown
        integ = shown
      }
    }

    // Ensure synthetic methods exist in the call graph
    for (ref <- syntheticMethods.distinct)
      if (!methods.exists(_.ref == ref))
        methods = methods :+ ExtractedMethod(ref.className, ref.packageName, ref.methodName, Nil)

    (methods, integ, orphaned)
  }
}

object LineageAdjustments {

  val empty: LineageAdjustments = LineageAdjustments()

  class ClassCollector {
    private val _refs                                  = ListBuffer.empty[(String, String)]
    def cls[T: ClassTag]: ClassCollector               = {
      val (pkg, name) = splitClassTag(summon[ClassTag[T]])
      _refs += ((pkg, name))
      this
    }
    def cls(pkg: String, name: String): ClassCollector = {
      _refs += ((pkg, name))
      this
    }
    def refs: Seq[(String, String)]                    = _refs.toSeq
  }

  def builder: Builder = new Builder

  class Builder {
    private val _adjustments     = ListBuffer.empty[LineageAdjustment]
    private val _undetectedTypes = scala.collection.mutable.Set.empty[ResourceType]

    /** Mark resource types as manual-only — class-level integrations of these types won't require auto-detection validation. By default all
      * class-level integrations expect scanner confirmation.
      */
    def undetected(resourceTypes: ResourceType*): Builder = {
      _undetectedTypes ++= resourceTypes
      this
    }

    // ── Type-safe selectors (compile-time checked) ────────────────────────

    /** Select a method by type and lambda — uses compile-time macro for safety. */
    inline def method[T](inline selector: T => Any): MethodActions = {
      val (pkg, cls, method) = MethodRefMacro.extract[T](selector)
      new MethodActions(pkg, cls, method)
    }

    /** Select all methods of a class by type. */
    def cls[T: ClassTag]: ClassActions = {
      val (pkg, cls) = splitClassTag(summon[ClassTag[T]])
      new ClassActions(pkg, cls)
    }

    /** Select multiple classes at once; apply actions to all of them. Usage: `.classes(_.cls[A].cls[B].cls[C]).show.setGroup("Group")`
      */
    def classes(selector: ClassCollector => ClassCollector): MultiClassActions = {
      val collector = selector(new ClassCollector)
      new MultiClassActions(collector.refs)
    }

    // ── String-based selectors (for non-TASTy / external elements) ────────

    /** Select a method by fully-qualified names. */
    def method(pkg: String, cls: String, method: String): MethodActions =
      new MethodActions(pkg, cls, method)

    /** Select all methods of a class by fully-qualified name. */
    def cls(pkg: String, cls: String): ClassActions =
      new ClassActions(pkg, cls)

    // ── Package selector ──────────────────────────────────────────────────

    /** Select all classes in a package prefix. */
    def pkg(packagePrefix: String): PackageActions =
      new PackageActions(packagePrefix)

    // ── Resource selector ─────────────────────────────────────────────────

    /** Select an external resource by type and target name. */
    def resource(resourceType: ResourceType, target: String): ResourceActions =
      new ResourceActions(resourceType, target)

    /** Select external resources by type and regex pattern on target name. Useful for batch operations like merging partition tables or removing
      * infrastructure noise.
      */
    def resourceMatching(resourceType: ResourceType, pattern: String): ResourcePatternActions =
      new ResourcePatternActions(resourceType, pattern.r)

    // ── Build ─────────────────────────────────────────────────────────────

    def build: LineageAdjustments = LineageAdjustments(_adjustments.toList)

    // ── Method-level actions ──────────────────────────────────────────────

    class MethodActions(packageName: String, className: String, methodName: String) {
      private def ref: MethodRef = MethodRef(packageName, className, methodName)

      // Add integration
      def reads: IntegrationBuilder     = new IntegrationBuilder(ref, DataAccessType.Read)
      def writes: IntegrationBuilder    = new IntegrationBuilder(ref, DataAccessType.Write)
      def readWrite: IntegrationBuilder = new IntegrationBuilder(ref, DataAccessType.ReadWrite)

      // Add call edge
      inline def calls[T](inline selector: T => Any): MethodActions      = {
        val (p, c, m) = MethodRefMacro.extract[T](selector)
        _adjustments += LineageAdjustment.AddCall(ref, MethodRef(p, c, m))
        this
      }
      def calls(pkg: String, cls: String, method: String): MethodActions = {
        _adjustments += LineageAdjustment.AddCall(ref, MethodRef(pkg, cls, method))
        this
      }

      // Remove call edge
      inline def removesCall[T](inline selector: T => Any): MethodActions      = {
        val (p, c, m) = MethodRefMacro.extract[T](selector)
        _adjustments += LineageAdjustment.RemoveCall(ref, MethodRef(p, c, m))
        this
      }
      def removesCall(pkg: String, cls: String, method: String): MethodActions = {
        _adjustments += LineageAdjustment.RemoveCall(ref, MethodRef(pkg, cls, method))
        this
      }

      // Remove specific integration
      def removeIntegration(resourceType: ResourceType, target: String): MethodActions = {
        _adjustments += LineageAdjustment.RemoveIntegration(packageName, className, Some(methodName), resourceType, target)
        this
      }

      // Remove all integrations of a type
      def removeIntegrations(resourceType: ResourceType): MethodActions = {
        _adjustments += LineageAdjustment.RemoveIntegrationsByType(packageName, className, Some(methodName), resourceType)
        this
      }

      // Override the resource identity of auto-detected integrations matching the resource type
      def overrideResource(resourceType: ResourceType, newResourceId: ResourceId): MethodActions = {
        _adjustments += LineageAdjustment.OverrideResource(packageName, className, Some(methodName), resourceType, newResourceId)
        this
      }

      // Hide the method (reconnect callers → callees, promote integrations)
      def remove: Builder = {
        _adjustments += LineageAdjustment.HideMethod(ref)
        Builder.this
      }

      // Hard-delete the method (disconnects the graph)
      def delete: Builder = {
        _adjustments += LineageAdjustment.DeleteMethod(ref)
        Builder.this
      }

      // Transitions to other selectors
      inline def method[T](inline selector: T => Any): MethodActions             = Builder.this.method[T](selector)
      def method(pkg: String, cls: String, method: String): MethodActions        = Builder.this.method(pkg, cls, method)
      def cls[T: ClassTag]: ClassActions                                         = Builder.this.cls[T]
      def cls(pkg: String, cls: String): ClassActions                            = Builder.this.cls(pkg, cls)
      def classes(selector: ClassCollector => ClassCollector): MultiClassActions = Builder.this.classes(selector)
      def pkg(packagePrefix: String): PackageActions                             = Builder.this.pkg(packagePrefix)
      def resource(resourceType: ResourceType, target: String): ResourceActions  = Builder.this.resource(resourceType, target)
      def build: LineageAdjustments                                              = Builder.this.build
    }

    // ── Class-level actions ───────────────────────────────────────────────

    class ClassActions(packageName: String, className: String) {

      // Add integration (class-level — resolves to matching methods at apply time)
      def reads: ClassIntegrationBuilder     = new ClassIntegrationBuilder(packageName, className, DataAccessType.Read)
      def writes: ClassIntegrationBuilder    = new ClassIntegrationBuilder(packageName, className, DataAccessType.Write)
      def readWrite: ClassIntegrationBuilder = new ClassIntegrationBuilder(packageName, className, DataAccessType.ReadWrite)

      // Add call edge (resolves "from" method at apply time)
      inline def calls[T](inline selector: T => Any): ClassActions      = {
        val (p, c, m) = MethodRefMacro.extract[T](selector)
        _adjustments += LineageAdjustment.AddClassCall(packageName, className, MethodRef(p, c, m))
        this
      }
      def calls(pkg: String, cls: String, method: String): ClassActions = {
        _adjustments += LineageAdjustment.AddClassCall(packageName, className, MethodRef(pkg, cls, method))
        this
      }

      // Remove call edge from all methods in this class
      inline def removesCall[T](inline selector: T => Any): ClassActions      = {
        val (p, c, m) = MethodRefMacro.extract[T](selector)
        _adjustments += LineageAdjustment.RemoveClassCall(packageName, className, MethodRef(p, c, m))
        this
      }
      def removesCall(pkg: String, cls: String, method: String): ClassActions = {
        _adjustments += LineageAdjustment.RemoveClassCall(packageName, className, MethodRef(pkg, cls, method))
        this
      }

      // Remove specific integration from all methods
      def removeIntegration(resourceType: ResourceType, target: String): ClassActions = {
        _adjustments += LineageAdjustment.RemoveIntegration(packageName, className, None, resourceType, target)
        this
      }

      // Remove all integrations of a type from all methods
      def removeIntegrations(resourceType: ResourceType): ClassActions = {
        _adjustments += LineageAdjustment.RemoveIntegrationsByType(packageName, className, None, resourceType)
        this
      }

      // Override the resource identity of auto-detected integrations matching the resource type
      def overrideResource(resourceType: ResourceType, newResourceId: ResourceId): ClassActions = {
        _adjustments += LineageAdjustment.OverrideResource(packageName, className, None, resourceType, newResourceId)
        this
      }

      // Rename class display label (does not change IDs or data)
      def renameTo(displayName: String): ClassActions = {
        _adjustments += LineageAdjustment.RenameClass(packageName, className, displayName)
        this
      }

      // Set rendering group for this class (metadata-only)
      def setGroup(group: String): ClassActions = {
        _adjustments += LineageAdjustment.SetClassGroup(packageName, className, group)
        this
      }

      // Explicitly show this class (when any ShowClass exists, non-shown classes are hidden)
      def show: ClassActions = {
        _adjustments += LineageAdjustment.ShowClass(packageName, className)
        this
      }

      // Hide the class (reconnect callers → callees, promote integrations)
      def remove: Builder = {
        _adjustments += LineageAdjustment.HideClass(packageName, className)
        Builder.this
      }

      // Hard-delete the class (disconnects the graph)
      def delete: Builder = {
        _adjustments += LineageAdjustment.DeleteClass(packageName, className)
        Builder.this
      }

      // Transitions to other selectors
      inline def method[T](inline selector: T => Any): MethodActions             = Builder.this.method[T](selector)
      def method(pkg: String, cls: String, method: String): MethodActions        = Builder.this.method(pkg, cls, method)
      def cls[T: ClassTag]: ClassActions                                         = Builder.this.cls[T]
      def cls(pkg: String, cls: String): ClassActions                            = Builder.this.cls(pkg, cls)
      def classes(selector: ClassCollector => ClassCollector): MultiClassActions = Builder.this.classes(selector)
      def pkg(packagePrefix: String): PackageActions                             = Builder.this.pkg(packagePrefix)
      def resource(resourceType: ResourceType, target: String): ResourceActions  = Builder.this.resource(resourceType, target)
      def build: LineageAdjustments                                              = Builder.this.build
    }

    // ── Multi-class collector & actions ─────────────────────────────────

    class MultiClassActions(refs: Seq[(String, String)]) {
      def show: MultiClassActions                          = {
        refs.foreach { (pkg, cls) => _adjustments += LineageAdjustment.ShowClass(pkg, cls) }
        this
      }
      def setGroup(group: String): MultiClassActions       = {
        refs.foreach { (pkg, cls) => _adjustments += LineageAdjustment.SetClassGroup(pkg, cls, group) }
        this
      }
      def renameTo(displayName: String): MultiClassActions = {
        refs.foreach { (pkg, cls) => _adjustments += LineageAdjustment.RenameClass(pkg, cls, displayName) }
        this
      }
      def remove: Builder                                  = {
        refs.foreach { (pkg, cls) => _adjustments += LineageAdjustment.HideClass(pkg, cls) }
        Builder.this
      }
      def delete: Builder                                  = {
        refs.foreach { (pkg, cls) => _adjustments += LineageAdjustment.DeleteClass(pkg, cls) }
        Builder.this
      }

      // Transitions to other selectors
      inline def method[T](inline selector: T => Any): MethodActions                            = Builder.this.method[T](selector)
      def method(pkg: String, cls: String, method: String): MethodActions                       = Builder.this.method(pkg, cls, method)
      def cls[T: ClassTag]: ClassActions                                                        = Builder.this.cls[T]
      def cls(pkg: String, cls: String): ClassActions                                           = Builder.this.cls(pkg, cls)
      def classes(selector: ClassCollector => ClassCollector): MultiClassActions                = Builder.this.classes(selector)
      def pkg(packagePrefix: String): PackageActions                                            = Builder.this.pkg(packagePrefix)
      def resource(resourceType: ResourceType, target: String): ResourceActions                 = Builder.this.resource(resourceType, target)
      def resourceMatching(resourceType: ResourceType, pattern: String): ResourcePatternActions = Builder.this.resourceMatching(resourceType, pattern)
      def build: LineageAdjustments                                                             = Builder.this.build
    }

    // ── Package-level actions ──────────────────────────────────────────

    class PackageActions(packagePrefix: String) {

      // Set rendering group for all classes in this package prefix (metadata-only)
      def setGroup(group: String): PackageActions = {
        _adjustments += LineageAdjustment.SetPackageGroup(packagePrefix, group)
        this
      }

      // Hide all classes in this package prefix (reconnect callers → callees, promote integrations)
      def remove: Builder = {
        _adjustments += LineageAdjustment.HidePackage(packagePrefix)
        Builder.this
      }

      // Transitions to other selectors
      inline def method[T](inline selector: T => Any): MethodActions                            = Builder.this.method[T](selector)
      def method(pkg: String, cls: String, method: String): MethodActions                       = Builder.this.method(pkg, cls, method)
      def cls[T: ClassTag]: ClassActions                                                        = Builder.this.cls[T]
      def cls(pkg: String, cls: String): ClassActions                                           = Builder.this.cls(pkg, cls)
      def classes(selector: ClassCollector => ClassCollector): MultiClassActions                = Builder.this.classes(selector)
      def pkg(packagePrefix: String): PackageActions                                            = Builder.this.pkg(packagePrefix)
      def resource(resourceType: ResourceType, target: String): ResourceActions                 = Builder.this.resource(resourceType, target)
      def resourceMatching(resourceType: ResourceType, pattern: String): ResourcePatternActions = Builder.this.resourceMatching(resourceType, pattern)
      def build: LineageAdjustments                                                             = Builder.this.build
    }

    // ── Integration builders (method-level and class-level) ─────────────

    class IntegrationBuilder(method: MethodRef, accessType: DataAccessType) {
      private def emit(resourceId: ResourceId): IntegrationBuilder = {
        _adjustments += LineageAdjustment.AddIntegration(method, accessType, resourceId)
        this
      }

      def kafka(topic: String, cluster: Option[String] = None): IntegrationBuilder                     = emit(ResourceId.KafkaTopic(topic, cluster))
      def s3(path: String, bucket: Option[String] = None, region: Option[String] = None): IntegrationBuilder =
        emit(ResourceId.S3Object(path, bucket, region))
      def database(table: String, database: Option[String] = None, schema: Option[String] = None, cluster: Option[String] = None): IntegrationBuilder =
        emit(ResourceId.DbTable(table, schema = schema, database = database, cluster = cluster))
      def grpc(service: String, method: String, host: Option[String] = None): IntegrationBuilder       = emit(ResourceId.GrpcEndpoint(service, method, host))
      def journal(table: String = "journal", database: Option[String] = None): IntegrationBuilder      =
        emit(ResourceId.DbTable(table, database = database))
      def resource(resourceId: ResourceId): IntegrationBuilder                                         = emit(resourceId)
      def custom(resourceType: ResourceType, segments: List[(String, String)]): IntegrationBuilder     = emit(ResourceId.Generic(resourceType, segments))

      // Transitions to other selectors
      inline def method[T](inline selector: T => Any): MethodActions             = Builder.this.method[T](selector)
      def method(pkg: String, cls: String, method: String): MethodActions        = Builder.this.method(pkg, cls, method)
      def cls[T: ClassTag]: ClassActions                                         = Builder.this.cls[T]
      def cls(pkg: String, cls: String): ClassActions                            = Builder.this.cls(pkg, cls)
      def classes(selector: ClassCollector => ClassCollector): MultiClassActions = Builder.this.classes(selector)
      def pkg(packagePrefix: String): PackageActions                             = Builder.this.pkg(packagePrefix)
      def resource(resourceType: ResourceType, target: String): ResourceActions  = Builder.this.resource(resourceType, target)
      def build: LineageAdjustments                                              = Builder.this.build
    }

    class ClassIntegrationBuilder(packageName: String, className: String, accessType: DataAccessType) {
      private val startIdx = _adjustments.size

      private def emit(resourceId: ResourceId): ClassIntegrationBuilder = {
        val expect = !_undetectedTypes.contains(resourceId.resourceType)
        _adjustments += LineageAdjustment.AddClassIntegration(packageName, className, accessType, resourceId, expect)
        this
      }

      def kafka(topic: String, cluster: Option[String] = None): ClassIntegrationBuilder                     = emit(ResourceId.KafkaTopic(topic, cluster))
      def s3(path: String, bucket: Option[String] = None, region: Option[String] = None): ClassIntegrationBuilder =
        emit(ResourceId.S3Object(path, bucket, region))
      def database(table: String, database: Option[String] = None, schema: Option[String] = None, cluster: Option[String] = None): ClassIntegrationBuilder =
        emit(ResourceId.DbTable(table, schema = schema, database = database, cluster = cluster))
      def grpc(service: String, method: String, host: Option[String] = None): ClassIntegrationBuilder       = emit(ResourceId.GrpcEndpoint(service, method, host))
      def journal(table: String = "journal", database: Option[String] = None): ClassIntegrationBuilder      =
        emit(ResourceId.DbTable(table, database = database))
      def resource(resourceId: ResourceId): ClassIntegrationBuilder                                         = emit(resourceId)
      def custom(resourceType: ResourceType, segments: List[(String, String)]): ClassIntegrationBuilder     = emit(ResourceId.Generic(resourceType, segments))

      /** Mark entries from this builder as manual-only — no scanner detection expected. Overrides the default (which requires scanners to confirm the
        * resource type).
        */
      def undetected: ClassIntegrationBuilder = {
        for (i <- startIdx until _adjustments.size)
          _adjustments(i) match {
            case a: LineageAdjustment.AddClassIntegration => _adjustments(i) = a.copy(expectDetected = false)
            case _                                        =>
          }
        this
      }

      /** Mark entries from this builder as requiring scanner detection. Useful to override a builder-level `.undetected(type)` for specific entries.
        */
      def detected: ClassIntegrationBuilder = {
        for (i <- startIdx until _adjustments.size)
          _adjustments(i) match {
            case a: LineageAdjustment.AddClassIntegration => _adjustments(i) = a.copy(expectDetected = true)
            case _                                        =>
          }
        this
      }

      // Transitions to other selectors
      inline def method[T](inline selector: T => Any): MethodActions             = Builder.this.method[T](selector)
      def method(pkg: String, cls: String, method: String): MethodActions        = Builder.this.method(pkg, cls, method)
      def cls[T: ClassTag]: ClassActions                                         = Builder.this.cls[T]
      def cls(pkg: String, cls: String): ClassActions                            = Builder.this.cls(pkg, cls)
      def classes(selector: ClassCollector => ClassCollector): MultiClassActions = Builder.this.classes(selector)
      def pkg(packagePrefix: String): PackageActions                             = Builder.this.pkg(packagePrefix)
      def resource(resourceType: ResourceType, target: String): ResourceActions  = Builder.this.resource(resourceType, target)
      def build: LineageAdjustments                                              = Builder.this.build
    }

    // ── Resource-level actions ────────────────────────────────────────────

    class ResourceActions(resourceType: ResourceType, target: String) {

      /** Rename the resource label (innermost segment) across all integrations. */
      def renameTo(newTarget: String): ResourceActions = {
        _adjustments += LineageAdjustment.RenameResource(resourceType, target, newTarget)
        this
      }

      /** Replace the entire ResourceId for integrations matching this (type, label). */
      def renameTo(newResourceId: ResourceId): ResourceActions = {
        _adjustments += LineageAdjustment.ReplaceResourceId(resourceType, target, newResourceId)
        this
      }

      /** Remove all integrations pointing to this resource. */
      def remove: Builder = {
        _adjustments += LineageAdjustment.RemoveResource(resourceType, target)
        Builder.this
      }

      // Transitions to other selectors
      inline def method[T](inline selector: T => Any): MethodActions                            = Builder.this.method[T](selector)
      def method(pkg: String, cls: String, method: String): MethodActions                       = Builder.this.method(pkg, cls, method)
      def cls[T: ClassTag]: ClassActions                                                        = Builder.this.cls[T]
      def cls(pkg: String, cls: String): ClassActions                                           = Builder.this.cls(pkg, cls)
      def classes(selector: ClassCollector => ClassCollector): MultiClassActions                = Builder.this.classes(selector)
      def pkg(packagePrefix: String): PackageActions                                            = Builder.this.pkg(packagePrefix)
      def resource(resourceType: ResourceType, target: String): ResourceActions                 = Builder.this.resource(resourceType, target)
      def resourceMatching(resourceType: ResourceType, pattern: String): ResourcePatternActions = Builder.this.resourceMatching(resourceType, pattern)
      def build: LineageAdjustments                                                             = Builder.this.build
    }

    class ResourcePatternActions(resourceType: ResourceType, pattern: scala.util.matching.Regex) {

      /** Rename all matching resources to a single target (merges them into one node). */
      def renameTo(newTarget: String): ResourcePatternActions = {
        _adjustments += LineageAdjustment.RenameResourceByPattern(resourceType, pattern, newTarget)
        this
      }

      /** Remove all integrations whose target matches the pattern. */
      def remove: Builder = {
        _adjustments += LineageAdjustment.RemoveResourceByPattern(resourceType, pattern)
        Builder.this
      }

      // Transitions to other selectors
      inline def method[T](inline selector: T => Any): MethodActions                            = Builder.this.method[T](selector)
      def method(pkg: String, cls: String, method: String): MethodActions                       = Builder.this.method(pkg, cls, method)
      def cls[T: ClassTag]: ClassActions                                                        = Builder.this.cls[T]
      def cls(pkg: String, cls: String): ClassActions                                           = Builder.this.cls(pkg, cls)
      def classes(selector: ClassCollector => ClassCollector): MultiClassActions                = Builder.this.classes(selector)
      def pkg(packagePrefix: String): PackageActions                                            = Builder.this.pkg(packagePrefix)
      def resource(resourceType: ResourceType, target: String): ResourceActions                 = Builder.this.resource(resourceType, target)
      def resourceMatching(resourceType: ResourceType, pattern: String): ResourcePatternActions = Builder.this.resourceMatching(resourceType, pattern)
      def build: LineageAdjustments                                                             = Builder.this.build
    }
  }
}
