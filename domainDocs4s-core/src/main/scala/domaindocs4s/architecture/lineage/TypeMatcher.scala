package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.ClassSymbol
import tastyquery.Types.*

/** FQN-based type matching for [[SymbolUsageFinder]] searches.
  *
  * Wraps a `String => Boolean` predicate on fully qualified names.
  * When `checkAncestors` is true, the predicate is also applied
  * to the type's ancestor chain.
  *
  * {{{
  * // Exact FQN match
  * TypeMatcher("software.amazon.awssdk.services.s3.S3Client")
  *
  * // Match any of several FQNs
  * TypeMatcher.oneOf("...S3Client", "...S3AsyncClient")
  *
  * // Match types whose FQN ends with a suffix
  * TypeMatcher.fqnEndsWith("Fs2Grpc")
  *
  * // Match types that inherit from a given type
  * TypeMatcher.isOrInheritsFrom("org.apache.pekko.persistence.query.ReadJournal")
  *
  * // Arbitrary predicate on FQN
  * TypeMatcher(_.contains("aws"))
  * }}}
  */
case class TypeMatcher(
    matchFqn: String => Boolean,
    checkAncestors: Boolean = false,
)

object TypeMatcher {

  /** Matches a type with this exact fully qualified name. */
  def apply(fqn: String): TypeMatcher = new TypeMatcher(_ == fqn)

  /** Matches a type whose FQN is any of the given values. */
  def oneOf(fqns: String*): TypeMatcher = { val set = fqns.toSet; new TypeMatcher(set.contains) }

  /** Matches a type whose FQN ends with the given suffix. */
  def fqnEndsWith(suffix: String): TypeMatcher = new TypeMatcher(_.endsWith(suffix))

  /** Matches a type that is or inherits from a type with the given FQN.
    * Walks the full type hierarchy.
    */
  def isOrInheritsFrom(fqn: String): TypeMatcher = new TypeMatcher(_ == fqn, checkAncestors = true)
}

/** Internal resolver for matching TypeMatcher against TASTy types. */
private[lineage] object TypeMatcherResolver {

  /** Extract FQN from a TASTy type. */
  def fqnOf(tpe: TypeOrMethodic): Option[String] = TastyUtils.extractFqn(tpe)

  /** Check if a TypeMatcher matches a given TASTy type. */
  def matches(matcher: TypeMatcher, tpe: TypeOrMethodic)(using Context): Boolean =
    fqnOf(tpe).exists(matcher.matchFqn) ||
      (matcher.checkAncestors && hasMatchingAncestor(tpe, matcher.matchFqn, Set.empty))

  /** Check if a TypeMatcher matches a FQN string directly (for tree reference matching). */
  def matchesFqn(matcher: TypeMatcher, fqn: String): Boolean =
    matcher.matchFqn(fqn)

  /** Extract FQN from a TermRef (used for Ident/Select tree reference types).
    * Recursively resolves nested TermRef prefixes (e.g., `Producer.flexiFlow`
    * where `Producer` is itself a TermRef, not a PackageRef).
    */
  def termRefFqn(refType: Any): Option[String] = refType match {
    case tr: TermRef =>
      try {
        val prefix = tr.prefix match {
          case pr: PackageRef => pr.symbol.fullName.toString
          case inner: TermRef => termRefFqn(inner).getOrElse("")
          case _              => ""
        }
        val name = tr.name.toString.stripSuffix("$")
        if (prefix.nonEmpty) Some(s"$prefix.$name") else Some(name)
      } catch { case _: Exception => None }
    case _ => None
  }

  /** Walk type hierarchy checking for an ancestor matching the predicate. */
  private def hasMatchingAncestor(tpe: TypeOrMethodic, predicate: String => Boolean, visited: Set[ClassSymbol])(using Context): Boolean = {
    tpe match {
      case at: AndType => return hasMatchingAncestor(at.first, predicate, visited) || hasMatchingAncestor(at.second, predicate, visited)
      case _           =>
    }
    TastyUtils.resolveSymbol(tpe) match {
      case Some(cs: ClassSymbol) if !visited.contains(cs) =>
        try cs.parents.exists { p =>
          fqnOf(p).exists(predicate) || hasMatchingAncestor(p, predicate, visited + cs)
        } catch { case _: Exception => false }
      case _ => false
    }
  }
}
