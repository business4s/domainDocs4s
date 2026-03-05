package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.ClassSymbol
import tastyquery.Types.*

/** Structured type matching using fully qualified names (FQNs).
  *
  * Used in [[DeclarativeScanner]] rules to match types without
  * working directly with TASTy trees.
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
  * }}}
  */
sealed trait TypeMatcher

object TypeMatcher {

  /** Matches a type with this exact fully qualified name. */
  def apply(fqn: String): TypeMatcher = Exact(fqn)

  /** Matches a type whose FQN is any of the given values. */
  def oneOf(fqns: String*): TypeMatcher = OneOf(fqns.toSet)

  /** Matches a type whose FQN ends with the given suffix. */
  def fqnEndsWith(suffix: String): TypeMatcher = FqnEndsWith(suffix)

  /** Matches a type that is or inherits from a type with the given FQN.
    * Walks the full type hierarchy.
    */
  def isOrInheritsFrom(fqn: String): TypeMatcher = IsOrInheritsFrom(fqn)

  private[lineage] case class Exact(fqn: String) extends TypeMatcher
  private[lineage] case class OneOf(fqns: Set[String]) extends TypeMatcher
  private[lineage] case class FqnEndsWith(suffix: String) extends TypeMatcher
  private[lineage] case class IsOrInheritsFrom(fqn: String) extends TypeMatcher
}

/** Internal resolver for matching TypeMatcher against TASTy types. */
private[lineage] object TypeMatcherResolver {

  /** Extract FQN from a TASTy type. */
  def fqnOf(tpe: TypeOrMethodic): Option[String] = TastyUtils.extractFqn(tpe)

  /** Check if a TypeMatcher matches a given TASTy type. */
  def matches(matcher: TypeMatcher, tpe: TypeOrMethodic)(using Context): Boolean = matcher match {
    case TypeMatcher.Exact(fqn)            => fqnOf(tpe).contains(fqn)
    case TypeMatcher.OneOf(fqns)           => fqnOf(tpe).exists(fqns.contains)
    case TypeMatcher.FqnEndsWith(suffix)   => fqnOf(tpe).exists(_.endsWith(suffix))
    case TypeMatcher.IsOrInheritsFrom(fqn) => fqnOf(tpe).contains(fqn) || hasAncestorWithFqn(tpe, fqn, Set.empty)
  }

  /** Check if a TypeMatcher matches a FQN string directly (for tree reference matching). */
  def matchesFqn(matcher: TypeMatcher, fqn: String): Boolean = matcher match {
    case TypeMatcher.Exact(target)       => fqn == target
    case TypeMatcher.OneOf(targets)      => targets.contains(fqn)
    case TypeMatcher.FqnEndsWith(suffix) => fqn.endsWith(suffix)
    case TypeMatcher.IsOrInheritsFrom(_) => false // ancestry check requires type, not just FQN string
  }

  /** Extract FQN from a TermRef (used for Ident/Select tree reference types). */
  def termRefFqn(refType: Any): Option[String] = refType match {
    case tr: TermRef =>
      try {
        val prefix = tr.prefix match {
          case pr: PackageRef => pr.symbol.fullName.toString
          case _              => ""
        }
        val name = tr.name.toString.stripSuffix("$")
        if (prefix.nonEmpty) Some(s"$prefix.$name") else Some(name)
      } catch { case _: Exception => None }
    case _ => None
  }

  /** Walk type hierarchy checking for an ancestor with the given FQN. */
  private def hasAncestorWithFqn(tpe: TypeOrMethodic, targetFqn: String, visited: Set[ClassSymbol])(using Context): Boolean = {
    tpe match {
      case at: AndType => return hasAncestorWithFqn(at.first, targetFqn, visited) || hasAncestorWithFqn(at.second, targetFqn, visited)
      case _           =>
    }
    TastyUtils.resolveSymbol(tpe) match {
      case Some(cs: ClassSymbol) if !visited.contains(cs) =>
        try cs.parents.exists { p =>
          fqnOf(p).contains(targetFqn) || hasAncestorWithFqn(p, targetFqn, visited + cs)
        } catch { case _: Exception => false }
      case _ => false
    }
  }
}
