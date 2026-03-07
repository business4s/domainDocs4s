package domaindocs4s.architecture.lineage.example

import doobie.*
import doobie.implicits.*

// ============================================================================
// Example classes for trait-based dependency injection pattern.
//
// Architecture: AuditService -> AuditRepo (trait) -> AuditRepoImpl -> Database
//
// The call graph extractor must bridge AuditRepo.logAction → AuditRepoImpl.logAction
// so that the doobie integration on AuditRepoImpl is reachable from AuditService.
// ============================================================================

trait AuditRepo {
  def logAction(action: String): ConnectionIO[Int]
}

class AuditRepoImpl extends AuditRepo {
  def logAction(action: String): ConnectionIO[Int] =
    sql"INSERT INTO audit_log (action) VALUES ($action)".update.run
}

class AuditService(val auditRepo: AuditRepo) {
  def performAudit(action: String): ConnectionIO[Int] =
    auditRepo.logAction(action)
}
