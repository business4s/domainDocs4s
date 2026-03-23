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

/** Same pattern but without val — simulates a constructor param used in method body. The call graph must still detect the field.method() call.
  */
class AuditServiceNoVal(auditRepo: AuditRepo) {
  def performAudit(action: String): ConnectionIO[Int] =
    auditRepo.logAction(action)
}

/** Repo where a public method delegates to a private helper that has the actual doobie call. The call graph must detect the intra-class call:
  * batchLog → insertBatch.
  */
class AuditRepoWithHelper extends AuditRepo {
  def logAction(action: String): ConnectionIO[Int] =
    insertBatch(List(action))

  private def insertBatch(actions: List[String]): ConnectionIO[Int] = {
    val sql = "INSERT INTO audit_log (action) VALUES (?)"
    Update[String](sql).updateMany(actions)
  }
}
