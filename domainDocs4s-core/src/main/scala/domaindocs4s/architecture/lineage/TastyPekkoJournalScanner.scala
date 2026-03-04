package domaindocs4s.architecture.lineage

import tastyquery.Contexts.Context
import tastyquery.Symbols.*
import tastyquery.Trees.*
import tastyquery.Traversers.*
import tastyquery.Types.*

import scala.collection.mutable.ListBuffer

// ============================================================================
// TASTy-based Pekko Journal Scanner
//
// Scans compiled Scala code via TASTy to find Pekko persistence integrations.
// Output: "classA.methodB reads/writes journal"
//
// Write side — Classic persistent actors:
//   class extends PersistentActor / AbstractPersistentActor
//   → Write to journal
//
// Write side — Typed event-sourced behaviors:
//   method body calls EventSourcedBehavior.apply / .withEnforcedReplies
//   → Write to journal
//
// Read side — Journal query consumers:
//   val field whose type has ReadJournal as ancestor
//   → each call to field.method(...) is a Read from journal
//
// Read side — Projection source providers:
//   method body references EventSourcedProvider (any method)
//   method body calls readJournalFor (PersistenceQuery pattern)
//   → Read from journal
// ============================================================================

class TastyPekkoJournalScanner(using ctx: Context) {

  private val PersistentActorNames = Set("PersistentActor", "AbstractPersistentActor")
  private val EventSourcedBehaviorName = "EventSourcedBehavior"
  private val ReadJournalName = "ReadJournal"
  private val EventSourcedProviderName = "EventSourcedProvider"
  private val ReadJournalForMethod = "readJournalFor"

  def scan(packageName: String): List[DiscoveredIntegration] = {
    val pkg = ctx.findPackage(packageName)
    val classes = TastyUtils.userClasses(pkg)
    val objects = TastyUtils.moduleClasses(pkg)
    classes.flatMap { cls =>
      scanClassicPersistentActor(cls) ++ scanEventSourcedBehavior(cls) ++ scanJournalReader(cls) ++ scanProjectionSource(cls)
    } ++ objects.flatMap { cls =>
      scanEventSourcedBehavior(cls) ++ scanJournalReader(cls) ++ scanProjectionSource(cls)
    }
  }

  // ── Write side: Classic PersistentActor ──────────────────────────────────

  /** Classic: class extends PersistentActor → Write to journal. */
  private def scanClassicPersistentActor(cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString
    val isPersistent = cls.parents.exists { parentType =>
      TastyUtils.extractTypeName(parentType).exists(PersistentActorNames.contains)
    }
    if (!isPersistent) return Nil

    List(DiscoveredIntegration(
      method = MethodRef(className, "receiveCommand"),
      accessType = DataAccessType.Write,
      integrationType = "pekko-journal",
      target = "journal",
      evidence = "extends PersistentActor",
      group = Some("Journal"),
    ))
  }

  // ── Write side: Typed EventSourcedBehavior ───────────────────────────────

  /** Typed: method body references EventSourcedBehavior → Write to journal. */
  private def scanEventSourcedBehavior(cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString.stripSuffix("$")
    cls.declarations.collect {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        ts.tree.toList.flatMap {
          case defDef: DefDef =>
            defDef.rhs.toList.flatMap { rhs =>
              val detector = new EventSourcedBehaviorDetector
              detector.traverse(rhs)
              if (detector.found) List(DiscoveredIntegration(
                method = MethodRef(className, methodName),
                accessType = DataAccessType.Write,
                integrationType = "pekko-journal",
                target = "journal",
                evidence = "calls EventSourcedBehavior",
                group = Some("Journal"),
              ))
              else Nil
            }
          case _ => Nil
        }
    }.flatten
  }

  // ── Read side: Journal query consumers ───────────────────────────────────

  /** Read: val fields whose type inherits ReadJournal → calls to those fields are Read from journal. */
  private def scanJournalReader(cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString.stripSuffix("$")
    val journalFields = resolveJournalFieldTypes(cls)
    if (journalFields.isEmpty) return Nil

    cls.declarations.collect {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        ts.tree.toList.flatMap {
          case defDef: DefDef =>
            defDef.rhs.toList.flatMap { rhs =>
              val collector = new JournalCallCollector(journalFields)
              collector.traverse(rhs)
              collector.calls.distinct.map { fieldName =>
                DiscoveredIntegration(
                  method = MethodRef(className, methodName),
                  accessType = DataAccessType.Read,
                  integrationType = "pekko-journal",
                  target = "journal",
                  evidence = s"calls $fieldName (ReadJournal)",
                  group = Some("Journal"),
                )
              }
            }
          case _ => Nil
        }
    }.flatten
  }

  /** Resolve val field types that inherit from ReadJournal. Returns Set[fieldName]. */
  private def resolveJournalFieldTypes(cls: ClassSymbol): Set[String] =
    cls.declarations.collect {
      case ts: TermSymbol if !ts.name.toString.startsWith("<") && !ts.tree.exists(_.isInstanceOf[DefDef]) =>
        if (isReadJournalType(ts.declaredType)) Some(ts.name.toString)
        else None
    }.flatten.toSet

  /** Check if a type is or inherits from ReadJournal. */
  private def isReadJournalType(tpe: TypeOrMethodic): Boolean = {
    TastyUtils.extractTypeName(tpe).contains(ReadJournalName) ||
      hasReadJournalAncestor(tpe, Set.empty)
  }

  /** Check ancestors of a type for ReadJournal, tracking visited symbols to handle diamond hierarchies. */
  private def hasReadJournalAncestor(tpe: TypeOrMethodic, visited: Set[ClassSymbol]): Boolean = {
    val sym = tpe match {
      case tr: TypeRef     => try tr.optSymbol catch { case _: Exception => None }
      case at: AppliedType => TastyUtils.extractTypeRef(at).flatMap(tr => try tr.optSymbol catch { case _: Exception => None })
      case at: AndType     => return hasReadJournalAncestor(at.first, visited) || hasReadJournalAncestor(at.second, visited)
      case _               => None
    }
    sym match {
      case Some(cs: ClassSymbol) if !visited.contains(cs) =>
        cs.parents.exists { p =>
          TastyUtils.extractTypeName(p).contains(ReadJournalName) || hasReadJournalAncestor(p, visited + cs)
        }
      case _ => false
    }
  }

  // ── Read side: Projection source providers ──────────────────────────────

  /** Projection sources: method body references EventSourcedProvider or calls readJournalFor → Read from journal. */
  private def scanProjectionSource(cls: ClassSymbol): List[DiscoveredIntegration] = {
    val className = cls.name.toString.stripSuffix("$")
    cls.declarations.collect {
      case ts: TermSymbol if ts.tree.exists(_.isInstanceOf[DefDef]) =>
        val methodName = ts.name.toString
        ts.tree.toList.flatMap {
          case defDef: DefDef =>
            defDef.rhs.toList.flatMap { rhs =>
              val detector = new ProjectionSourceDetector
              detector.traverse(rhs)
              if (detector.found) List(DiscoveredIntegration(
                method = MethodRef(className, methodName),
                accessType = DataAccessType.Read,
                integrationType = "pekko-journal",
                target = "journal",
                evidence = detector.evidence,
                group = Some("Journal"),
              ))
              else Nil
            }
          case _ => Nil
        }
    }.flatten
  }

  // ── Tree traversers ─────────────────────────────────────────────────────

  /** TreeTraverser that detects any reference to EventSourcedBehavior in a method body. */
  private class EventSourcedBehaviorDetector extends TreeTraverser {
    var found: Boolean = false

    override def traverse(tree: Tree): Unit = {
      if (!found) {
        tree match {
          case Ident(name) if name.toString == EventSourcedBehaviorName => found = true
          case Select(_, name) if name.toString == EventSourcedBehaviorName => found = true
          case _ =>
        }
        if (!found) super.traverse(tree)
      }
    }
  }

  /** TreeTraverser that collects calls to journal fields (field.method(...) patterns). */
  private class JournalCallCollector(journalFields: Set[String]) extends TreeTraverser {
    val calls: ListBuffer[String] = ListBuffer.empty

    override def traverse(tree: Tree): Unit = {
      tree match {
        case Apply(Select(Ident(fieldName), _), _) =>
          addIfJournal(fieldName.toString)
        case Apply(TypeApply(Select(Ident(fieldName), _), _), _) =>
          addIfJournal(fieldName.toString)
        case _ =>
      }
      super.traverse(tree)
    }

    private def addIfJournal(fieldName: String): Unit =
      if (journalFields.contains(fieldName)) calls += fieldName
  }

  /** TreeTraverser that detects EventSourcedProvider references or readJournalFor calls. */
  private class ProjectionSourceDetector extends TreeTraverser {
    var foundEventSourcedProvider: Boolean = false
    var foundReadJournalFor: Boolean = false

    def found: Boolean = foundEventSourcedProvider || foundReadJournalFor

    def evidence: String = (foundEventSourcedProvider, foundReadJournalFor) match {
      case (true, true)   => "calls EventSourcedProvider, readJournalFor"
      case (true, false)  => "calls EventSourcedProvider"
      case (false, true)  => "calls readJournalFor"
      case (false, false) => ""
    }

    override def traverse(tree: Tree): Unit = {
      if (!(foundEventSourcedProvider && foundReadJournalFor)) {
        tree match {
          case Ident(name) if TastyUtils.simpleName(name) == EventSourcedProviderName     => foundEventSourcedProvider = true
          case Select(_, name) if TastyUtils.simpleName(name) == EventSourcedProviderName => foundEventSourcedProvider = true
          case Select(_, name) if TastyUtils.simpleName(name) == ReadJournalForMethod     => foundReadJournalFor = true
          case _ =>
        }
        super.traverse(tree)
      }
    }
  }
}
