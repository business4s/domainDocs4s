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

// ── Factory pattern: Table/TableQuery inside anonymous class in companion ────
//
// This pattern is common with Pekko projections + Slick, where Table subclasses
// and TableQuery fields are defined inside a companion object's apply() method
// body, and an anonymous class implements a trait using those fields.

trait FactoryRepo {
  def getOrders(userId: Long): DBIO[Seq[(Long, Long, String)]]
  def insertOrder(id: Long, userId: Long, item: String): DBIO[Int]
  def getItems(orderId: Long): DBIO[Seq[(Long, Long, Int)]]
  def deleteItem(itemId: Long): DBIO[Int]
}

object FactoryRepo {
  def apply(): FactoryRepo = {
    // Table with string literal name → resolves to "factory_orders"
    class OrderTable(tag: Tag) extends Table[(Long, Long, String)](tag, "factory_orders") {
      def id = column[Long]("id", O.PrimaryKey)
      def userId = column[Long]("user_id")
      def item = column[String]("item")
      def * = (id, userId, item)
    }

    // Table with variable name → resolves to "<unresolved:ItemTable>"
    val itemTableName = "factory_items"
    class ItemTable(tag: Tag) extends Table[(Long, Long, Int)](tag, itemTableName) {
      def id = column[Long]("id", O.PrimaryKey)
      def orderId = column[Long]("order_id")
      def quantity = column[Int]("quantity")
      def * = (id, orderId, quantity)
    }

    val orders = TableQuery[OrderTable]
    val items = TableQuery[ItemTable]

    new FactoryRepo {
      def getOrders(userId: Long) = orders.filter(_.userId === userId).result
      def insertOrder(id: Long, userId: Long, item: String) = orders.insertOrUpdate((id, userId, item))
      def getItems(orderId: Long) = items.filter(_.orderId === orderId).result
      def deleteItem(itemId: Long) = items.filter(_.id === itemId).delete
    }
  }
}

// ── Top-level repository ────────────────────────────────────────────────────

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
