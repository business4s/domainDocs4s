package domaindocs4s.architecture.lineage.example

import doobie.*
import doobie.implicits.*
import cats.syntax.all.*

// ============================================================================
// Example service layers with real doobie for TASTy scanning.
//
// Architecture: UserGrpcApi -> UserService -> UserRepo -> Database
//
// UserRepo uses real doobie: sql"..." interpolation, .query[T].unique,
// .update.run, etc. The TASTy scanner detects these patterns.
// ============================================================================

// ── Domain types ─────────────────────────────────────────────────────────────

case class Transaction(id: Long, userId: Long, amount: BigDecimal, description: String)
object Transaction {
  given Read[Transaction] = Read.derived
  given Write[Transaction] = Write.derived
}

case class GetBalanceRequest(userId: Long)
case class GetBalanceResponse(balance: BigDecimal)
case class DepositRequest(userId: Long, amount: BigDecimal)
case class DepositResponse(success: Boolean)
case class GetHistoryRequest(userId: Long)
case class GetHistoryResponse(transactions: List[Transaction])

// ============================================================================
// Service layers — real method bodies that produce inspectable TASTy
// ============================================================================

/** Repository — direct database access via doobie queries. */
class UserRepo {

  def getBalance(userId: Long): ConnectionIO[BigDecimal] =
    sql"SELECT balance FROM users WHERE id = $userId".query[BigDecimal].unique

  def getTransactions(userId: Long): ConnectionIO[List[Transaction]] =
    sql"SELECT id, user_id, amount, description FROM transactions WHERE user_id = $userId"
      .query[Transaction].to[List]

  def insertTransaction(tx: Transaction): ConnectionIO[Int] =
    sql"INSERT INTO transactions (user_id, amount, description) VALUES (${tx.userId}, ${tx.amount}, ${tx.description})"
      .update.run

  def updateBalance(userId: Long, newBalance: BigDecimal): ConnectionIO[Int] =
    sql"UPDATE users SET balance = $newBalance WHERE id = $userId"
      .update.run
}

/** Service — business logic, orchestrates repository calls. */
class UserService(val repo: UserRepo) {

  def getBalance(userId: Long): ConnectionIO[BigDecimal] =
    repo.getBalance(userId)

  def deposit(userId: Long, amount: BigDecimal): ConnectionIO[Unit] =
    for {
      _ <- repo.updateBalance(userId, amount)
      _ <- repo.insertTransaction(Transaction(0, userId, amount, "deposit"))
    } yield ()

  def getHistory(userId: Long): ConnectionIO[List[Transaction]] =
    repo.getTransactions(userId)
}

/** gRPC API — entry point, delegates to service. */
class UserGrpcApi(val service: UserService) {

  def getBalance(req: GetBalanceRequest): ConnectionIO[GetBalanceResponse] =
    service.getBalance(req.userId).map(GetBalanceResponse(_))

  def deposit(req: DepositRequest): ConnectionIO[DepositResponse] =
    service.deposit(req.userId, req.amount).as(DepositResponse(true))

  def getHistory(req: GetHistoryRequest): ConnectionIO[GetHistoryResponse] =
    service.getHistory(req.userId).map(GetHistoryResponse(_))
}
