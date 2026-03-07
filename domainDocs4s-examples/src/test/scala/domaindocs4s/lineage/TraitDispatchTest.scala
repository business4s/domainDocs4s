package domaindocs4s.lineage

import domaindocs4s.architecture.lineage.*
import domaindocs4s.collector.TastyContext
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers.*
import tastyquery.Contexts.Context

class TraitDispatchTest extends AnyFreeSpec {

  given ctx: Context = TastyContext.fromCurrentProcess()

  private val pkg = "domaindocs4s.architecture.lineage.example"

  private val callGraph = new TastyCallGraphExtractor().extract(pkg)
  private val doobieIntegrations = new TastyDoobieScanner().scan(List(pkg))
  private val result = LineageBuilder.build(callGraph, doobieIntegrations)

  "TastyCallGraphExtractor trait dispatch" - {

    "AuditRepoImpl is present in the call graph" in {
      val classNames = callGraph.map(_.className).distinct
      classNames should contain("AuditRepoImpl")
    }

    "AuditRepo.logAction has a bridge call to AuditRepoImpl.logAction" in {
      val auditRepoMethods = callGraph.filter(_.className == "AuditRepo")
      val logAction = auditRepoMethods.find(_.methodName == "logAction")
      logAction shouldBe defined
      logAction.get.calls should contain(MethodRef(pkg, "AuditRepoImpl", "logAction"))
    }

    "AuditService.performAudit calls AuditRepo.logAction" in {
      val serviceMethods = callGraph.filter(_.className == "AuditService")
      val performAudit = serviceMethods.find(_.methodName == "performAudit")
      performAudit shouldBe defined
      performAudit.get.calls should contain(MethodRef(pkg, "AuditRepo", "logAction"))
    }
  }

  "LineageBuilder with trait dispatch" - {

    "builds lineage chain from AuditService through AuditRepo to audit_log" in {
      val chains = result.lineageFrom(MethodRef(pkg, "AuditService", "performAudit"))
      chains should not be empty

      val auditLogChains = chains.filter(_.integration.target == "audit_log")
      auditLogChains should have size 1
      auditLogChains.head.path.map(_.className) shouldBe List("AuditService", "AuditRepo", "AuditRepoImpl")
    }
  }
}
