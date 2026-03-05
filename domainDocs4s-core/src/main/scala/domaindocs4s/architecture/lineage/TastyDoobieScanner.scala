package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Types.*

import scala.collection.mutable.ListBuffer

// ============================================================================
// TASTy-based Doobie Scanner
//
// Scans compiled Scala code via TASTy to find doobie query invocations.
// Output: "classA.methodB reads/writes tableC"
//
// Detection: finds methods returning ConnectionIO[_], then matches AST patterns:
//   sql"...".query[T].unique   → Read
//   sql"...".query[T].to[F]    → Read
//   sql"...".query[T].option   → Read
//   sql"...".update.run         → Write
//
// SQL extraction: collects string literal parts from the sql"..." interpolator
// (StringContext.apply("part1", "part2", ...)) and joins them to recover the
// SQL template. Table names are extracted via regex from the joined SQL.
// ============================================================================

class TastyDoobieScanner(
    connectionIOTypeName: String = "ConnectionIO",
)(using ctx: Context) extends IntegrationScanner {

  def scan(packages: List[String]): List[DiscoveredIntegration] =
    packages.flatMap(scanPackage)

  private def scanPackage(packageName: String): List[DiscoveredIntegration] = {
    val pkg = ctx.findPackage(packageName)
    val classesWithPkg = TastyUtils.userClassesRecursive(pkg)

    classesWithPkg.flatMap { case (ownerPkg, cls) =>
      val className = cls.name.toString.stripSuffix("$")
      val pkgName = ownerPkg.fullName.toString
      cls.declarations.collect {
        case ts: TermSymbol if returnsConnectionIO(ts.declaredType) =>
          val ref = MethodRef(pkgName, className, ts.name.toString)
          ts.tree.toList.flatMap {
            case defDef: DefDef => defDef.rhs.toList.flatMap(rhs => findDoobieOps(ref, rhs))
            case _              => Nil
          }
      }.flatten
    }
  }

  private def returnsConnectionIO(tpe: TypeOrMethodic): Boolean = tpe match {
    case mt: MethodType  => returnsConnectionIO(mt.resultType)
    case pt: PolyType    => returnsConnectionIO(pt.resultType)
    case at: AppliedType => at.tycon.isInstanceOf[TypeRef] && at.tycon.asInstanceOf[TypeRef].name.toString == connectionIOTypeName
    case tr: TypeRef     => tr.name.toString == connectionIOTypeName
    case _               => false
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
    case _                   => ()
  }

  private def isReadTerminal(name: tastyquery.Names.Name): Boolean =
    nm(name, "unique") || nm(name, "option")

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
