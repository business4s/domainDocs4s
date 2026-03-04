package domaindocs4s.architecture.lineage.example.slick

import slick.jdbc.H2Profile.api.*

// ============================================================================
// Example Slick classes for TASTy scanning.
//
// Two Table subclasses with static table names.
// A repository class with methods returning DBIO[_]:
//   - lifted embedding: .result (Read), .insertOrUpdate (Write),
//     ++= (Write), .delete (Write)
//   - plain SQL: sql"...".as[T] (Read), sqlu"..." (Write)
// ============================================================================

// ── Table definitions ────────────────────────────────────────────────────────

class AccountBalanceTable(tag: Tag) extends Table[(Long, BigDecimal)](tag, "account_balances") {
  def id = column[Long]("id", O.PrimaryKey)
  def balance = column[BigDecimal]("balance")
  def * = (id, balance)
}

class TransactionTable(tag: Tag) extends Table[(Long, Long, BigDecimal, String)](tag, "slick_transactions") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def userId = column[Long]("user_id")
  def amount = column[BigDecimal]("amount")
  def description = column[String]("description")
  def * = (id, userId, amount, description)
}

// ── TableQuery vals ──────────────────────────────────────────────────────────

object SlickTables {
  val accountBalances = TableQuery[AccountBalanceTable]
  val transactions = TableQuery[TransactionTable]
}

// ── Repository ───────────────────────────────────────────────────────────────

class SlickRepo {

  import SlickTables.*

  /** Lifted embedding — .result → Read */
  def getBalance(userId: Long): DBIO[Option[(Long, BigDecimal)]] =
    accountBalances.filter(_.id === userId).result.headOption

  /** Lifted embedding — .result → Read */
  def listTransactions(userId: Long): DBIO[Seq[(Long, Long, BigDecimal, String)]] =
    transactions.filter(_.userId === userId).result

  /** Lifted embedding — insertOrUpdate → Write */
  def upsertBalance(id: Long, balance: BigDecimal): DBIO[Int] =
    accountBalances.insertOrUpdate((id, balance))

  /** Lifted embedding — ++= → Write */
  def insertTransactions(rows: Seq[(Long, Long, BigDecimal, String)]): DBIO[Option[Int]] =
    transactions ++= rows

  /** Lifted embedding — .delete → Write */
  def deleteTransaction(txId: Long): DBIO[Int] =
    transactions.filter(_.id === txId).delete

  /** Plain SQL — sql"...".as[T] → Read */
  def getBalancePlainSql(userId: Long): DBIO[Option[BigDecimal]] =
    sql"SELECT balance FROM account_balances WHERE id = $userId".as[BigDecimal].headOption

  /** Plain SQL — sqlu"..." → Write */
  def updateBalancePlainSql(userId: Long, newBalance: BigDecimal): DBIO[Int] =
    sqlu"UPDATE account_balances SET balance = $newBalance WHERE id = $userId"
}
