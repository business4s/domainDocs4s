package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*
import tastyquery.Types.*

import scala.collection.mutable.ListBuffer

// ============================================================================
// Declarative Scanner API
//
// High-level API for defining custom integration scanners without
// direct TASTy tree manipulation. Uses three detection primitives:
//   - FieldMethodCall: field.method() patterns
//   - ClassExtends: class inheritance patterns
//   - TypeReference: identifier reference patterns
//
// All type matching uses fully qualified names (FQNs) via TypeMatcher.
// ============================================================================

/** How to map method calls to access types. */
sealed trait MethodMapping

object MethodMapping {

  /** Specific methods with their access types. */
  case class Named(
      readMethods: Set[String] = Set.empty,
      writeMethods: Set[String] = Set.empty,
  ) extends MethodMapping

  /** Any method call matches with a fixed access type. */
  case class AnyMethod(accessType: DataAccessType) extends MethodMapping
}

/** Context provided to target/group naming lambdas in detection rules.
  *
  * @param typeName      simple name of the matched type (field type, parent type, or referenced type)
  * @param className     name of the class being scanned
  * @param methodName    name of the method being scanned
  * @param calledMethod  the specific method called on a field or implemented from a parent
  */
type MatchContext = (typeName: Option[String], className: String, methodName: String, calledMethod: Option[String])

/** A detection rule that defines how to identify an integration pattern. */
sealed trait DetectionRule

object DetectionRule {

  /** Detects when a class field of a matching type has methods called on it.
    *
    * {{{
    * DetectionRule.FieldMethodCall(
    *   fieldType = TypeMatcher.oneOf(
    *     "software.amazon.awssdk.services.s3.S3Client",
    *     "software.amazon.awssdk.services.s3.S3AsyncClient",
    *   ),
    *   methods = MethodMapping.Named(
    *     writeMethods = Set("putObject", "copyObject"),
    *     readMethods = Set("getObject", "headObject"),
    *   ),
    *   target = m => s"${m.typeName.getOrElse("unknown")}/${m.calledMethod.getOrElse("")}",
    * )
    * }}}
    *
    * @param target when None, uses the scanner's `defaultTarget`
    * @param group  when None, uses the scanner's `group`
    */
  case class FieldMethodCall(
      fieldType: TypeMatcher,
      methods: MethodMapping,
      target: Option[MatchContext => String] = None,
      group: Option[MatchContext => Option[String]] = None,
  ) extends DetectionRule

  /** Detects when a class extends/implements a matching type.
    *
    * When `emitPerMethod` is true, emits one integration per method
    * inherited from the matched parent (e.g., gRPC server RPC methods).
    * Otherwise, emits a single integration for the class.
    *
    * {{{
    * DetectionRule.ClassExtends(
    *   parentType = TypeMatcher.fqnEndsWith("Fs2Grpc"),
    *   accessType = DataAccessType.Write,
    *   emitPerMethod = true,
    *   target = m => s"${m.typeName.getOrElse("").stripSuffix("Fs2Grpc")}/${m.calledMethod.getOrElse("")}",
    *   group = m => m.typeName.map(_.stripSuffix("Fs2Grpc")),
    * )
    * }}}
    *
    * @param target when None, uses the scanner's `defaultTarget`
    * @param group  when None, uses the scanner's `group`
    */
  case class ClassExtends(
      parentType: TypeMatcher,
      accessType: DataAccessType,
      emitPerMethod: Boolean = false,
      methodName: String = "<class>",
      target: Option[MatchContext => String] = None,
      group: Option[MatchContext => Option[String]] = None,
  ) extends DetectionRule

  /** Detects when a matching type is referenced anywhere in a method body.
    *
    * {{{
    * DetectionRule.TypeReference(
    *   targetType = TypeMatcher("org.apache.pekko.kafka.scaladsl.Producer"),
    *   accessType = DataAccessType.Write,
    *   target = m => s"unknown kafka from ${m.className}.${m.methodName}",
    * )
    * }}}
    *
    * @param target when None, uses the scanner's `defaultTarget`
    * @param group  when None, uses the scanner's `group`
    */
  case class TypeReference(
      targetType: TypeMatcher,
      accessType: DataAccessType,
      target: Option[MatchContext => String] = None,
      group: Option[MatchContext => Option[String]] = None,
  ) extends DetectionRule
}

/** High-level scanner that detects integrations based on declarative rules.
  *
  * Extend this class to define a scanner using [[DetectionRule]]s and [[TypeMatcher]]s
  * instead of manipulating TASTy trees directly.
  *
  * {{{
  * class MyScanner(using Context) extends DeclarativeScanner(
  *   name = "my-scanner",
  *   resourceType = ResourceType("my-resource"),
  *   rules = Seq(
  *     DetectionRule.FieldMethodCall(
  *       fieldType = TypeMatcher("com.example.MyClient"),
  *       methods = MethodMapping.Named(writeMethods = Set("send")),
  *       target = m => s"${m.typeName.getOrElse("unknown")}/${m.calledMethod.getOrElse("")}",
  *     ),
  *   ),
  * )
  * }}}
  */
class DeclarativeScanner(
    val name: String,
    val resourceType: ResourceType,
    val rules: Seq[DetectionRule],
    val defaultTarget: String = "",
    val group: Option[String] = None,
)(using ctx: Context) extends IntegrationScanner {

  def scan(packages: List[String]): List[DiscoveredIntegration] =
    packages.flatMap(scanPackage)

  private def scanPackage(packageName: String): List[DiscoveredIntegration] = {
    val pkg = ctx.findPackage(packageName)
    val userWithPkg = TastyUtils.userClassesRecursive(pkg)
    val moduleWithPkg = TastyUtils.moduleClassesRecursive(pkg)
    val allWithPkg = userWithPkg ++ moduleWithPkg
    allWithPkg.flatMap { case (ownerPkg, cls) => scanClass(ownerPkg.fullName.toString, cls) }
  }

  private def scanClass(packageName: String, cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString.stripSuffix("$")
    rules.flatMap {
      case r: DetectionRule.FieldMethodCall => evaluateFieldMethodCall(packageName, className, cls, r)
      case r: DetectionRule.ClassExtends    => evaluateClassExtends(packageName, className, cls, r)
      case r: DetectionRule.TypeReference   => evaluateTypeReference(packageName, className, cls, r)
    }.toList
  }

  /** Iterate over all method bodies in a class, invoking `f` for each (methodName, rhs). */
  private def forEachMethodBody(cls: ClassSymbol)(
      f: (String, Tree) => List[DiscoveredIntegration],
  ): List[DiscoveredIntegration] =
    cls.declarations.collect {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        ts.tree.toList.flatMap {
          case defDef: DefDef => defDef.rhs.toList.flatMap(rhs => f(methodName, rhs))
          case _              => Nil
        }
    }.flatten

  // ── FieldMethodCall ──────────────────────────────────────────────────

  private def evaluateFieldMethodCall(
      packageName: String,
      className: String,
      cls: ClassSymbol,
      rule: DetectionRule.FieldMethodCall,
  ): List[DiscoveredIntegration] = {
    // Pre-scan fields: find fields whose type matches the rule's fieldType
    val matchingFields: Map[String, Option[String]] = cls.declarations.collect {
      case ts: TermSymbol if !ts.name.toString.startsWith("<") && !ts.tree.exists(_.isInstanceOf[DefDef]) =>
        if (TypeMatcherResolver.matches(rule.fieldType, ts.declaredType))
          Some(ts.name.toString -> TastyUtils.extractTypeName(ts.declaredType))
        else None
    }.flatten.toMap

    if (matchingFields.isEmpty) return Nil

    // Scan method bodies for field.method() patterns
    forEachMethodBody(cls) { (methodName, rhs) =>
      val collector = new FieldMethodCallCollector(matchingFields.keySet, rule.methods)
      collector.traverse(rhs)
      collector.calls.distinct.toList.map { case (fieldName, calledMethod, accessType) =>
        val typeName = matchingFields(fieldName)
        val ctx: MatchContext = (typeName = typeName, className = className, methodName = methodName, calledMethod = Some(calledMethod))
        DiscoveredIntegration(
          method = MethodRef(packageName, className, methodName),
          accessType = accessType,
          resourceType = resourceType,
          scanner = name,
          target = rule.target.map(_(ctx)).getOrElse(defaultTarget),
          evidence = s"calls $fieldName.$calledMethod",
          group = rule.group.map(_(ctx)).getOrElse(group),
        )
      }
    }
  }

  // ── ClassExtends ─────────────────────────────────────────────────────

  private def evaluateClassExtends(
      packageName: String,
      className: String,
      cls: ClassSymbol,
      rule: DetectionRule.ClassExtends,
  ): List[DiscoveredIntegration] = {
    val matchedParents = try cls.parents.filter { parentType =>
      TypeMatcherResolver.matches(rule.parentType, parentType)
    } catch { case _: Exception => Nil }

    if (matchedParents.isEmpty) return Nil

    matchedParents.flatMap { parentType =>
      val parentTypeName = TastyUtils.extractTypeName(parentType)

      if (rule.emitPerMethod) {
        val parentMethods = resolveParentMethods(parentType)
        cls.declarations.collect {
          case ts: TermSymbol if parentMethods.contains(ts.name.toString) =>
            val methodName = ts.name.toString
            val ctx: MatchContext = (typeName = parentTypeName, className = className, methodName = methodName, calledMethod = Some(methodName))
            DiscoveredIntegration(
              method = MethodRef(packageName, className, methodName),
              accessType = rule.accessType,
              resourceType = resourceType,
              scanner = name,
              target = rule.target.map(_(ctx)).getOrElse(defaultTarget),
              evidence = s"implements ${parentTypeName.getOrElse("?")}",
              group = rule.group.map(_(ctx)).getOrElse(group),
            )
        }
      } else {
        val ctx: MatchContext = (typeName = parentTypeName, className = className, methodName = rule.methodName, calledMethod = None)
        List(DiscoveredIntegration(
          method = MethodRef(packageName, className, rule.methodName),
          accessType = rule.accessType,
          resourceType = resourceType,
          scanner = name,
          target = rule.target.map(_(ctx)).getOrElse(defaultTarget),
          evidence = s"extends ${parentTypeName.getOrElse("?")}",
          group = rule.group.map(_(ctx)).getOrElse(group),
        ))
      }
    }
  }

  /** Resolve a parent type to a ClassSymbol and get its declared method names. */
  private def resolveParentMethods(parentType: TypeOrMethodic): Set[String] =
    TastyUtils.resolveSymbol(parentType) match {
      case Some(cs: ClassSymbol) =>
        cs.declarations.collect {
          case ts: TermSymbol if isUserDeclaration(ts) => ts.name.toString
        }.toSet
      case _ => Set.empty
    }

  private def isUserDeclaration(ts: TermSymbol): Boolean = {
    val name = ts.name.toString
    !name.startsWith("<") && !name.startsWith("$")
  }

  // ── TypeReference ────────────────────────────────────────────────────

  private def evaluateTypeReference(
      packageName: String,
      className: String,
      cls: ClassSymbol,
      rule: DetectionRule.TypeReference,
  ): List[DiscoveredIntegration] =
    forEachMethodBody(cls) { (methodName, rhs) =>
      val detector = new TypeReferenceDetector(rule.targetType)
      detector.traverse(rhs)
      if (detector.found) {
        val ctx: MatchContext = (typeName = detector.matchedName, className = className, methodName = methodName, calledMethod = None)
        List(DiscoveredIntegration(
          method = MethodRef(packageName, className, methodName),
          accessType = rule.accessType,
          resourceType = resourceType,
          scanner = name,
          target = rule.target.map(_(ctx)).getOrElse(defaultTarget),
          evidence = detector.evidence,
          group = rule.group.map(_(ctx)).getOrElse(group),
        ))
      } else Nil
    }

  // ── Tree traversers ──────────────────────────────────────────────────

  private class FieldMethodCallCollector(
      matchingFields: Set[String],
      methods: MethodMapping,
  ) extends TreeTraverser {
    val calls: ListBuffer[(String, String, DataAccessType)] = ListBuffer.empty

    override def traverse(tree: Tree): Unit = {
      tree match {
        case Apply(Select(Ident(fieldName), methodName), _) =>
          checkCall(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case Apply(TypeApply(Select(Ident(fieldName), methodName), _), _) =>
          checkCall(TastyUtils.simpleName(fieldName), TastyUtils.simpleName(methodName))
        case _ =>
      }
      super.traverse(tree)
    }

    private def checkCall(fieldName: String, methodName: String): Unit = {
      if (matchingFields.contains(fieldName)) {
        methods match {
          case MethodMapping.Named(readMethods, writeMethods) =>
            if (writeMethods.contains(methodName))
              calls += ((fieldName, methodName, DataAccessType.Write))
            else if (readMethods.contains(methodName))
              calls += ((fieldName, methodName, DataAccessType.Read))
          case MethodMapping.AnyMethod(accessType) =>
            calls += ((fieldName, methodName, accessType))
        }
      }
    }
  }

  private class TypeReferenceDetector(targetType: TypeMatcher) extends TreeTraverser {
    private var matchedRef: Option[String] = None

    def found: Boolean = matchedRef.isDefined
    def matchedName: Option[String] = matchedRef

    def evidence: String = matchedRef match {
      case Some(ref) => s"references $ref"
      case None      => "references target type"
    }

    override def traverse(tree: Tree): Unit = {
      if (!found) {
        tree match {
          case ident: Ident => tryMatchRef(ident)
          case _            =>
        }
        if (!found) super.traverse(tree)
      }
    }

    private def tryMatchRef(tree: TermReferenceTree): Unit = {
      try {
        val refType = tree.referenceType
        TypeMatcherResolver.termRefFqn(refType).foreach { fqn =>
          if (TypeMatcherResolver.matchesFqn(targetType, fqn))
            matchedRef = Some(fqn.split('.').last)
        }
      } catch { case _: Exception => }
    }
  }
}
