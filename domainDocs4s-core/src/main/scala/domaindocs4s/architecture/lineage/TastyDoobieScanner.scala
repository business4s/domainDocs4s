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
//   sql"...".query[T].unique            → Read
//   sql"...".query[T].to[F]             → Read
//   sql"...".query[T].option            → Read
//   sql"...".query[T].stream            → Read
//   sql"...".update.run                  → Write
//   Update[A](sql).updateMany(data)      → Write
//   Update[A](sql).withGeneratedKeys(ks) → Write
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
    methods.flatMap(m => findDoobieOps(m.ref, m.rhs)).filter(_.target != "unknown")
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
  //   Update[A](sql).updateMany(data)      → Apply(Select(receiver, updateMany), _)
  //   Update[A](sql).withGeneratedKeys(ks) → Apply(Select(receiver, withGeneratedKeys), _)
  private def walk(tree: Tree, out: ListBuffer[DiscoveredIntegration], method: MethodRef, valBindings: Map[String, Tree] = Map.empty): Unit = tree match {
    // .query[T](read).unique / .query[T](read).option → Read
    case Select(Apply(TypeApply(Select(frag, q), _), _), terminal) if nm(q, "query") && isReadTerminal(terminal) =>
      SqlUtils.sqlFrom(frag, valBindings).foreach(sql => out += mkIntegration(method, DataAccessType.Read, sql))

    // .query[T](read).to[F](fc) → Read
    case Apply(TypeApply(Select(Apply(TypeApply(Select(frag, q), _), _), t), _), _) if nm(q, "query") && nm(t, "to") =>
      SqlUtils.sqlFrom(frag, valBindings).foreach(sql => out += mkIntegration(method, DataAccessType.Read, sql))

    // .update.run → Write
    case Select(Select(frag, u), r) if nm(u, "update") && nm(r, "run") =>
      SqlUtils.sqlFrom(frag, valBindings).foreach(sql => out += mkIntegration(method, DataAccessType.Write, sql))

    // Update[A](sql).updateMany(data) / Update[A](sql).withGeneratedKeys(...) → Write
    // TASTy shape: Apply(Apply(TypeApply(Select(receiver, updateMany), _), _), _)
    // We match Select(receiver, terminal) inside any nesting of Apply/TypeApply.
    case UpdateTerminal(receiver) =>
      SqlUtils.sqlFrom(receiver, valBindings).foreach { sql =>
        if (SqlUtils.looksLikeSql(sql)) out += mkIntegration(method, DataAccessType.Write, sql)
      }

    // Recurse
    case Apply(fun, args)    => walk(fun, out, method, valBindings); args.foreach(walk(_, out, method, valBindings))
    case TypeApply(fun, _)   => walk(fun, out, method, valBindings)
    case Block(stats, expr)  =>
      val newBindings = stats.foldLeft(valBindings) {
        case (acc, vd: ValDef) => vd.rhs.fold(acc)(rhs => acc + (vd.name.toString -> rhs))
        case (acc, _)          => acc
      }
      stats.foreach(walk(_, out, method, newBindings)); walk(expr, out, method, newBindings)
    case t: ValDef           => t.rhs.foreach(walk(_, out, method, valBindings))
    case Select(qual, _)     => walk(qual, out, method, valBindings)
    case Inlined(body, _, _) => walk(body, out, method, valBindings)
    case If(_, thenp, elsep) => walk(thenp, out, method, valBindings); walk(elsep, out, method, valBindings)
    case Match(_, cases)     => cases.foreach(c => walk(c.body, out, method, valBindings))
    case Try(body, catches, finalizer) =>
      walk(body, out, method, valBindings)
      catches.foreach(c => walk(c.body, out, method, valBindings))
      finalizer.foreach(walk(_, out, method, valBindings))
    case l: Lambda           => walk(l.meth, out, method, valBindings)
    case _                   => ()
  }

  private def isReadTerminal(name: tastyquery.Names.Name): Boolean =
    nm(name, "unique") || nm(name, "option") || nm(name, "stream")

  private def isUpdateTerminal(name: tastyquery.Names.Name): Boolean =
    nm(name, "updateMany") || nm(name, "withGeneratedKeys")

  /** Custom extractor: unwrap Apply/TypeApply layers to find Select(receiver, updateMany|withGeneratedKeys). */
  private object UpdateTerminal {
    def unapply(tree: Tree): Option[Tree] = tree match {
      case Select(receiver, name) if isUpdateTerminal(name) => Some(receiver)
      case Apply(fun, _)    => unapply(fun)
      case TypeApply(fun, _) => unapply(fun)
      case _                 => None
    }
  }

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

  /** SQL keywords and PostgreSQL functions that should never be treated as table names. */
  private val sqlKeywords = Set(
    "select", "from", "where", "set", "values", "join", "inner", "outer", "left", "right",
    "cross", "full", "on", "and", "or", "not", "in", "exists", "as", "order", "group", "by",
    "having", "limit", "offset", "union", "intersect", "except", "case", "when", "then", "else",
    "end", "null", "true", "false", "is", "like", "between", "distinct", "all", "any",
    "lateral", "unnest", "generate_series", "jsonb_each_text", "jsonb_each", "json_each",
    "jsonb_array_elements", "json_array_elements",
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
    * For sql"..." / sqlu"..." interpolation, the TASTy tree contains a StringContext.apply(...)
    * call with the SQL template parts as string literals inside a SeqLiteral.
    * We collect all string literals from the tree and join them.
    *
    * `valBindings` maps local val names to their RHS trees, allowing resolution of
    * references like `val q = "INSERT INTO ..."; Update[Row](q).updateMany(data)`.
    */
  def sqlFrom(tree: Tree, valBindings: Map[String, Tree] = Map.empty): Option[String] = {
    val parts = ListBuffer.empty[String]
    collectStringLiterals(tree, parts, valBindings)
    val sql = parts.mkString
    if (sql.nonEmpty) Some(sql) else None
  }

  private def collectStringLiterals(tree: Tree, parts: ListBuffer[String], valBindings: Map[String, Tree]): Unit = tree match {
    case Literal(c) if c.value.isInstanceOf[String] =>
      parts += c.value.asInstanceOf[String]
    case Ident(name) if valBindings.contains(name.toString) =>
      collectStringLiterals(valBindings(name.toString), parts, valBindings)
    case Apply(fun, args) =>
      collectStringLiterals(fun, parts, valBindings); args.foreach(collectStringLiterals(_, parts, valBindings))
    case TypeApply(fun, _) =>
      collectStringLiterals(fun, parts, valBindings)
    case Select(qual, _) =>
      collectStringLiterals(qual, parts, valBindings)
    case t: Typed =>
      collectStringLiterals(t.expr, parts, valBindings)
    case t: SeqLiteral =>
      t.elems.foreach(collectStringLiterals(_, parts, valBindings))
    case _ => ()
  }
}
