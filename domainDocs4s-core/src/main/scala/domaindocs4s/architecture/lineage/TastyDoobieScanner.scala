package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Trees.*

// ============================================================================
// TASTy-based Doobie Scanner
//
// Scans compiled Scala code via TASTy to find doobie query invocations.
// Output: "classA.methodB reads/writes tableC"
//
// Uses SymbolUsageFinder with type-based searches to detect doobie patterns:
//   Fragment.query     → Read  (sql"...".query[T].unique/option/stream/to)
//   Fragment.update    → Write (sql"...".update.run)
//   Query0.unique/option/stream/to → Read
//   Update.updateMany/withGeneratedKeys → Write
//
// Type resolution uses Strategy 4 (return-type propagation via SignedName
// signatures) which reliably resolves doobie expression types without
// computing .tpe (which fails for doobie TASTy in tasty-query 1.7.0).
//
// SQL extraction: collects string literal parts from the sql"..." interpolator
// (StringContext.apply("part1", "part2", ...)) and joins them to recover the
// SQL template. Table names are extracted via regex from the joined SQL.
// ============================================================================

class TastyDoobieScanner()(using ctx: Context) extends IntegrationScanner {

  // Type-based searches for doobie patterns
  private val fragmentSearch = SymbolSearch.MethodCall(TypeMatcher.fqnEndsWith("fragment$.Fragment"))
  private val query0Search   = SymbolSearch.MethodCall(TypeMatcher.fqnEndsWith("query$.Query0"))
  private val updateSearch   = SymbolSearch.MethodCall(TypeMatcher.fqnEndsWith("update$.Update"))

  private val readMethods  = Set("unique", "option", "stream", "to")
  private val writeMethods = Set("updateMany", "withGeneratedKeys")

  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val finder = new SymbolUsageFinder(Seq(fragmentSearch, query0Search, updateSearch))
    val usages = finder.findAll(packages)

    val classified = usages.collect { case u: FoundUsage.MethodCallResult => u }

    // Pre-compute val bindings per enclosing method body (avoid repeated full-tree traversals
    // when a single method body contains multiple doobie usages).
    val valBindingsCache = scala.collection.mutable.Map.empty[Tree, Map[String, Tree]]

    classified
      .flatMap { u =>
        classifyUsage(u).flatMap { accessType =>
          val valBindings = enclosingMethodRhs(u.path) match {
            case Some(rhs) => valBindingsCache.getOrElseUpdate(rhs, collectBlockBindings(rhs))
            case None      => Map.empty[String, Tree]
          }
          val sql         = SqlUtils.sqlFrom(u.tree, valBindings).orElse(SqlUtils.sqlFrom(u.receiverTree, valBindings))
          sql.filter(SqlUtils.looksLikeSql).map { s =>
            DiscoveredIntegration(
              method = u.path.toMethodRef,
              accessType = accessType,
              resourceType = ResourceType.Database,
              scanner = "doobie",
              target = SqlUtils.extractTableName(s),
              evidence = s,
            )
          }
        }
      }
      .filter(_.target != "unknown")
      .distinct
  }

  /** Find the RHS tree of the enclosing method from a NestingPath. */
  private def enclosingMethodRhs(path: NestingPath): Option[Tree] =
    path.nodes.reverse.collectFirst { case NestingNode.Method(_, Some(dd)) => dd }.flatMap(_.rhs)

  private def collectBlockBindings(tree: Tree): Map[String, Tree] =
    SqlUtils.collectTreeBindings(tree)

  private def classifyUsage(u: FoundUsage.MethodCallResult): Option[DataAccessType] = {
    val method = u.methodName
    u.search match {
      case `fragmentSearch` =>
        if (method == "query") Some(DataAccessType.Read)
        else if (method == "update") Some(DataAccessType.Write)
        else None
      case `query0Search`   =>
        if (readMethods.contains(method)) Some(DataAccessType.Read)
        else None
      case `updateSearch`   =>
        if (writeMethods.contains(method)) Some(DataAccessType.Write)
        else None
      case _                => None
    }
  }
}

private[lineage] object SqlUtils {

  import tastyquery.Trees.*

  import scala.collection.mutable.ListBuffer

  /** Recursively collect val bindings (name → RHS tree) from all scopes in a tree. */
  def collectTreeBindings(tree: Tree): Map[String, Tree] = {
    val bindings = scala.collection.mutable.Map.empty[String, Tree]
    new tastyquery.Traversers.TreeTraverser {
      override def traverse(tree: Tree): Unit = tree match {
        case vd: ValDef =>
          vd.rhs.foreach(rhs => bindings += (vd.name.toString -> rhs))
          super.traverse(tree)
        case _          => super.traverse(tree)
      }
    }.traverse(tree)
    bindings.toMap
  }

  private val tablePatterns = List(
    "(?i)\\bFROM\\s+(\\w+)".r,
    "(?i)\\bINTO\\s+(\\w+)".r,
    "(?i)\\bUPDATE\\s+(\\w+)".r,
    "(?i)\\bDELETE\\s+FROM\\s+(\\w+)".r,
    "(?i)\\bTRUNCATE\\s+(?:TABLE\\s+)?(\\w+)".r,
  )

  /** SQL keywords and PostgreSQL functions that should never be treated as table names. */
  private val sqlKeywords = Set(
    "select",
    "from",
    "where",
    "set",
    "values",
    "join",
    "inner",
    "outer",
    "left",
    "right",
    "cross",
    "full",
    "on",
    "and",
    "or",
    "not",
    "in",
    "exists",
    "as",
    "order",
    "group",
    "by",
    "having",
    "limit",
    "offset",
    "union",
    "intersect",
    "except",
    "case",
    "when",
    "then",
    "else",
    "end",
    "null",
    "true",
    "false",
    "is",
    "like",
    "between",
    "distinct",
    "all",
    "any",
    "lateral",
    "unnest",
    "generate_series",
    "jsonb_each_text",
    "jsonb_each",
    "json_each",
    "jsonb_array_elements",
    "json_array_elements",
  )

  def extractTableName(sql: String): String =
    tablePatterns.iterator
      .flatMap(_.findAllMatchIn(sql))
      .map(_.group(1))
      .find(name => !sqlKeywords.contains(name.toLowerCase))
      .getOrElse("unknown")

  /** Check if a string looks like SQL (contains at least one SQL keyword the table patterns recognize). */
  def looksLikeSql(sql: String): Boolean =
    tablePatterns.exists(_.findFirstIn(sql).isDefined)

  /** Extract SQL from a string interpolation tree by collecting all string literal parts.
    *
    * For sql"..." / sqlu"..." interpolation, the TASTy tree contains a StringContext.apply(...) call with the SQL template parts as string literals
    * inside a SeqLiteral. We collect all string literals from the tree and join them.
    *
    * `valBindings` maps local val names to their RHS trees, allowing resolution of references like `val q = "INSERT INTO ...";
    * Update[Row](q).updateMany(data)`.
    */
  def sqlFrom(tree: Tree, valBindings: Map[String, Tree] = Map.empty): Option[String] = {
    val parts = ListBuffer.empty[String]
    collectStringLiterals(tree, parts, valBindings)
    val sql   = parts.mkString.stripMargin
    if (sql.nonEmpty) Some(sql) else None
  }

  /** Reconstruct a string from a string interpolation tree by interleaving StringContext parts with resolved interpolation arguments.
    *
    * Unlike `sqlFrom` which collects all string literals in tree traversal order (losing interleaving), this method reconstructs the original string
    * by:
    *   1. Extracting the StringContext literal parts from the receiver tree 2. Extracting the interpolation arguments from the Apply args 3.
    *      Resolving each argument to a string literal via val bindings where possible 4. Interleaving: parts(0) + args(0) + parts(1) + args(1) + ...
    *
    * Returns the reconstructed string without SQL validation — callers should apply `looksLikeSql` and any framework-specific post-processing
    * themselves.
    *
    * Falls back to `sqlFrom` if the tree doesn't match the interpolation pattern.
    */
  def sqlFromInterpolation(tree: Tree, valBindings: Map[String, Tree] = Map.empty): Option[String] = {
    // tree is typically: Apply(Select(receiver, "sqlu"/"sql"), [interpArgs])
    val (receiverTree, interpArgs) = tree match {
      case Apply(Select(recv, _), args)               => (recv, args)
      case Apply(TypeApply(Select(recv, _), _), args) => (recv, args)
      case Apply(recv, args)                          => (recv, args)
      case _                                          => return sqlFrom(tree, valBindings)
    }

    // Extract ordered StringContext literal parts from the receiver
    val scParts = extractStringContextParts(receiverTree)
    if (scParts.isEmpty) return sqlFrom(tree, valBindings)

    // Unwrap Typed + SeqLiteral if present (Scala 3 varargs representation)
    val flatArgs = interpArgs.flatMap {
      case t: Typed       =>
        t.expr match {
          case sl: SeqLiteral => sl.elems
          case other          => List(other)
        }
      case sl: SeqLiteral => sl.elems
      case other          => List(other)
    }

    // Resolve each interpolation arg to a string where possible
    val resolvedArgs = flatArgs.map(resolveToString(_, valBindings))

    // Interleave: parts(0) + args(0) + parts(1) + args(1) + ... + parts(n)
    val sb = new StringBuilder
    for (i <- scParts.indices) {
      sb.append(scParts(i))
      if (i < resolvedArgs.length) resolvedArgs(i).foreach(sb.append)
    }

    val sql = sb.toString.stripMargin
    if (sql.nonEmpty) Some(sql)
    else sqlFrom(tree, valBindings)
  }

  /** Extract the ordered string literal parts from a StringContext expression tree. Walks through implicit conversions, Apply, Typed, and TypeApply
    * wrappers to find the SeqLiteral containing the parts.
    */
  private def extractStringContextParts(tree: Tree): List[String] = tree match {
    case Apply(_, args)    =>
      // Look for SeqLiteral (or Typed(SeqLiteral)) among args
      val fromSeq = args
        .collectFirst {
          case sl: SeqLiteral =>
            sl.elems.collect { case Literal(c) if c.value.isInstanceOf[String] => c.value.asInstanceOf[String] }
          case t: Typed       =>
            t.expr match {
              case sl: SeqLiteral =>
                sl.elems.collect { case Literal(c) if c.value.isInstanceOf[String] => c.value.asInstanceOf[String] }
              case _              => Nil
            }
        }
        .filter(_.nonEmpty)
      fromSeq.getOrElse {
        // Recurse into args looking for the StringContext deeper in the tree
        args.flatMap(extractStringContextParts)
      }
    case t: Typed          => extractStringContextParts(t.expr)
    case Select(qual, _)   => extractStringContextParts(qual)
    case TypeApply(fun, _) => extractStringContextParts(fun)
    case _                 => Nil
  }

  /** Try to resolve a tree to a string literal value, following val bindings. Unwraps implicit conversion wrappers (e.g., Slick's TypedParameter) and
    * TypeApply nodes to reach the actual value reference.
    */
  def resolveToString(tree: Tree, valBindings: Map[String, Tree]): Option[String] = tree match {
    case Literal(c) if c.value.isInstanceOf[String]                   => Some(c.value.asInstanceOf[String])
    case Ident(name) if valBindings.contains(name.toString)           => resolveToString(valBindings(name.toString), valBindings)
    case Select(_: This, name) if valBindings.contains(name.toString) => resolveToString(valBindings(name.toString), valBindings)
    // Unwrap implicit conversion wrappers: Apply(fun, args) — try fun first, then args
    case Apply(fun, args)                                             =>
      resolveToString(fun, valBindings)
        .orElse(args.flatMap(resolveToString(_, valBindings)).headOption)
    // Unwrap TypeApply wrappers
    case TypeApply(fun, _)                                            => resolveToString(fun, valBindings)
    case _                                                            => None
  }

  private def collectStringLiterals(tree: Tree, parts: ListBuffer[String], valBindings: Map[String, Tree]): Unit = tree match {
    case Literal(c) if c.value.isInstanceOf[String]         =>
      parts += c.value.asInstanceOf[String]
    case Ident(name) if valBindings.contains(name.toString) =>
      collectStringLiterals(valBindings(name.toString), parts, valBindings)
    case Apply(fun, args)                                   =>
      collectStringLiterals(fun, parts, valBindings); args.foreach(collectStringLiterals(_, parts, valBindings))
    case TypeApply(fun, _)                                  =>
      collectStringLiterals(fun, parts, valBindings)
    case Select(qual, _)                                    =>
      collectStringLiterals(qual, parts, valBindings)
    case t: Typed                                           =>
      collectStringLiterals(t.expr, parts, valBindings)
    case t: SeqLiteral                                      =>
      t.elems.foreach(collectStringLiterals(_, parts, valBindings))
    case _                                                  => ()
  }
}
