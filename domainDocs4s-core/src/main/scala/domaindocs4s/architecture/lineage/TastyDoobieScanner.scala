package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Trees.*

import scala.collection.mutable.ListBuffer

// ============================================================================
// TASTy-based Doobie Scanner
//
// Scans compiled Scala code via TASTy to find doobie query invocations.
// Output: "classA.methodB reads/writes tableC"
//
// Detection: walks all method/val bodies and matches AST patterns:
//   sql"...".query[T].unique   → Read
//   sql"...".query[T].to[F]    → Read
//   sql"...".query[T].option   → Read
//   sql"...".query[T].stream   → Read
//   sql"...".update.run         → Write
//
// No return-type filtering — a method that contains sql"...".update.run
// writes to the table regardless of whether it returns ConnectionIO, IO,
// or anything else (e.g. after .transact(xa)).
//
// SQL extraction: collects string literal parts from the sql"..." interpolator
// (StringContext.apply("part1", "part2", ...)) and joins them to recover the
// SQL template. Table names are extracted via regex from the joined SQL.
//
// Uses SymbolUsageFinder.enumerateMethodBodies() for uniform class/method
// enumeration (including anonymous classes). Keeps custom tree matching for
// doobie-specific chain patterns.
// ============================================================================

class TastyDoobieScanner()(using ctx: Context) extends IntegrationScanner {

  //> This whole scanner looks a bit off. I especially dont like enumerateMethodBodies. Id rather look for anything using strign interpolator (sql/fr) through usual search and go from there
  def scan(packages: List[String]): List[DiscoveredIntegration] = {
    val methods = SymbolUsageFinder.enumerateMethodBodies(packages)
    methods.flatMap(m => findDoobieOps(m.ref, m.rhs))
  }

  private def findDoobieOps(method: MethodRef, tree: Tree): List[DiscoveredIntegration] = {
    val out = ListBuffer.empty[DiscoveredIntegration]
    walk(tree, out, method)
    out.toList
  }

  // The walk method matches doobie query/update chains and extracts the fragment
  // tree. With real doobie, .query[T] takes an implicit Read[T] argument, so the
  // TASTy has an extra Apply wrapping the TypeApply:
  //   .query[T](readInstance).unique       → Select(Apply(TypeApply(Select(frag, query), _), _), unique)
  //   .query[T](readInstance).to[F](fc)    → Apply(TypeApply(Select(Apply(TypeApply(Select(frag, query), _), _), to), _), _)
  //   .query[T](readInstance).option       → Select(Apply(TypeApply(Select(frag, query), _), _), option)
  //   .update.run                          → Select(Select(frag, update), run)
  private def walk(tree: Tree, out: ListBuffer[DiscoveredIntegration], method: MethodRef): Unit = tree match {
    // .query[T](read).unique / .query[T](read).option → Read
    case Select(Apply(TypeApply(Select(frag, q), _), _), terminal) if nm(q, "query") && isReadTerminal(terminal) =>
      SqlUtils.sqlFrom(frag).foreach(sql => out += mkIntegration(method, DataAccessType.Read, sql))

    // .query[T](read).to[F](fc) → Read
    case Apply(TypeApply(Select(Apply(TypeApply(Select(frag, q), _), _), t), _), _) if nm(q, "query") && nm(t, "to") =>
      SqlUtils.sqlFrom(frag).foreach(sql => out += mkIntegration(method, DataAccessType.Read, sql))

    // .update.run → Write
    case Select(Select(frag, u), r) if nm(u, "update") && nm(r, "run") =>
      SqlUtils.sqlFrom(frag).foreach(sql => out += mkIntegration(method, DataAccessType.Write, sql))

    // Recurse
    case Apply(fun, args)    => walk(fun, out, method); args.foreach(walk(_, out, method))
    case TypeApply(fun, _)   => walk(fun, out, method)
    case Block(stats, expr)  => stats.foreach(walk(_, out, method)); walk(expr, out, method)
    case t: ValDef           => t.rhs.foreach(walk(_, out, method))
    case Select(qual, _)     => walk(qual, out, method)
    case Inlined(body, _, _) => walk(body, out, method)
    case _                   => ()
  }

  private def isReadTerminal(name: tastyquery.Names.Name): Boolean =
    nm(name, "unique") || nm(name, "option") || nm(name, "stream")

  private def nm(name: tastyquery.Names.Name, target: String): Boolean =
    TastyUtils.matchesName(name, target)

  private def mkIntegration(method: MethodRef, access: DataAccessType, sql: String): DiscoveredIntegration =
    DiscoveredIntegration(
      method = method,
      accessType = access,
      resourceType = ResourceType.Database,
      scanner = "doobie",
      target = SqlUtils.extractTableName(sql),
      evidence = sql,
    )
}

private[lineage] object SqlUtils {

  import tastyquery.Trees.*

  import scala.collection.mutable.ListBuffer

  private val tablePatterns = List(
    "(?i)\\bFROM\\s+(\\w+)".r,
    "(?i)\\bINTO\\s+(\\w+)".r,
    "(?i)\\bUPDATE\\s+(\\w+)".r,
    "(?i)\\bDELETE\\s+FROM\\s+(\\w+)".r,
  )

  def extractTableName(sql: String): String =
    tablePatterns.iterator.flatMap(_.findFirstMatchIn(sql)).nextOption() match {
      case Some(m) => m.group(1)
      case None    => "unknown"
    }

  /** Extract SQL from a string interpolation tree by collecting all string literal parts.
    *
    * For sql"..." / sqlu"..." interpolation, the TASTy tree contains a StringContext.apply(...)
    * call with the SQL template parts as string literals inside a SeqLiteral.
    * We collect all string literals from the tree and join them.
    */
  def sqlFrom(tree: Tree): Option[String] = {
    val parts = ListBuffer.empty[String]
    collectStringLiterals(tree, parts)
    val sql = parts.mkString
    if (sql.nonEmpty) Some(sql) else None
  }

  private def collectStringLiterals(tree: Tree, parts: ListBuffer[String]): Unit = tree match {
    case Literal(c) if c.value.isInstanceOf[String] =>
      parts += c.value.asInstanceOf[String]
    case Apply(fun, args) =>
      collectStringLiterals(fun, parts); args.foreach(collectStringLiterals(_, parts))
    case TypeApply(fun, _) =>
      collectStringLiterals(fun, parts)
    case Select(qual, _) =>
      collectStringLiterals(qual, parts)
    case t: Typed =>
      collectStringLiterals(t.expr, parts)
    case t: SeqLiteral =>
      t.elems.foreach(collectStringLiterals(_, parts))
    case _ => ()
  }
}
