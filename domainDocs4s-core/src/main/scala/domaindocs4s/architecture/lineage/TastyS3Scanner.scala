package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*

import scala.collection.mutable.ListBuffer

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
)(using ctx: Context) extends IntegrationScanner {

  private val S3ClientNames = Set("S3Client", "S3AsyncClient")

  private val WriteMethods = Set(
    "putObject", "uploadPart", "copyObject",
    "deleteObject", "deleteObjects",
  )

  private val ReadMethods = Set(
    "getObject", "getObjectAsBytes", "headObject",
    "listObjects", "listObjectsV2",
  )

  def scan(packages: List[String]): List[DiscoveredIntegration] =
    packages.flatMap(scanPackage)

  private def scanPackage(packageName: String): List[DiscoveredIntegration] = {
    val pkg = ctx.findPackage(packageName)
    val classes = TastyUtils.userClasses(pkg)
    val objects = TastyUtils.moduleClasses(pkg)
    (classes ++ objects).flatMap(scanClass(packageName, _))
  }

  private def scanClass(packageName: String, cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString.stripSuffix("$")
    val s3Fields = resolveS3FieldNames(cls)
    if (s3Fields.isEmpty) return Nil

    cls.declarations.collect {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        ts.tree.toList.flatMap {
          case defDef: DefDef =>
            defDef.rhs.toList.flatMap { rhs =>
              val collector = new S3CallCollector(s3Fields)
              collector.traverse(rhs)
              collector.calls.distinct.map { case (s3Method, accessType) =>
                DiscoveredIntegration(
                  method = MethodRef(packageName, className, methodName),
                  accessType = accessType,
                  resourceType = ResourceType.S3,
                  scanner = "s3",
                  target = "S3",
                  evidence = s"calls s3Client.$s3Method",
                  group = group,
                )
              }
            }
          case _ => Nil
        }
    }.flatten
  }

  /** Resolve val/var field names whose type is S3Client or S3AsyncClient. */
  private def resolveS3FieldNames(cls: ClassSymbol): Set[String] =
    cls.declarations.collect {
      case ts: TermSymbol if !ts.name.toString.startsWith("<") && !ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val typeName = TastyUtils.extractTypeName(ts.declaredType)
        if (typeName.exists(S3ClientNames.contains)) Some(ts.name.toString)
        else None
    }.flatten.toSet

  /** TreeTraverser that collects S3 client method calls on known S3 fields. */
  private class S3CallCollector(s3Fields: Set[String]) extends TreeTraverser {
    val calls: ListBuffer[(String, DataAccessType)] = ListBuffer.empty

    override def traverse(tree: Tree): Unit = {
      tree match {
        // field.method(args) or field.method[T](args)
        case Apply(Select(qual, method), _) =>
          checkS3Call(qual, method)
        case Apply(TypeApply(Select(qual, method), _), _) =>
          checkS3Call(qual, method)
        case _ =>
      }
      super.traverse(tree)
    }

    private def checkS3Call(qual: Tree, method: tastyquery.Names.Name): Unit = {
      val fieldName = qual match {
        case Ident(name) => Some(TastyUtils.simpleName(name))
        case _           => None
      }
      fieldName.foreach { fn =>
        if (s3Fields.contains(fn)) {
          val mn = TastyUtils.simpleName(method)
          if (WriteMethods.contains(mn))
            calls += ((mn, DataAccessType.Write))
          else if (ReadMethods.contains(mn))
            calls += ((mn, DataAccessType.Read))
        }
      }
    }
  }
}
