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

// ── SimpleDBIO s"..." interpolation ─────────────────────────────────────────
//
// When Slick's SimpleDBIO is used with raw JDBC, the SQL is often in a plain
// s"..." interpolation rather than sql"..."/sqlu"...". The scanner detects
// StringContext.s calls and infers Read/Write from the SQL's first keyword.

class SimpleDbioRepo {
  def insertRow(id: Int): Unit = {
    val sql = s"INSERT INTO simple_dbio_table VALUES ($id)"
    ()
  }
  def selectRow(id: Int): Unit = {
    val sql = s"SELECT * FROM simple_dbio_read_table WHERE id = $id"
    ()
  }
  def nonSqlString(source: String): Unit = {
    val msg = s"Processing data from $source"
    ()
  }
}

// ── SQL variable resolution via sqlu with class-level val ───────────────────
//
// The sqlu interpolation uses a class-level val (#$tableName) for the table
// name. The scanner resolves it via collectValBindings + sqlFromInterpolation.

class SqlVarRepo {
  private val tableName = "interp_table"
  def insertWithInterp(id: Int): DBIO[Int] =
    sqlu"INSERT INTO #$tableName VALUES ($id)"
}

// ── Val-bound table name ────────────────────────────────────────────────────
//
// A regular class with a Table subclass whose constructor arg is a val reference
// rather than a string literal. The scanner resolves it via scope val bindings.

class ValBoundTableRepo {
  private val myTableName = "val_bound_table"
  class ValBoundTable(tag: Tag) extends Table[Int](tag, myTableName) {
    def id = column[Int]("id", O.PrimaryKey)
    def * = id
  }
  val valBoundQuery = TableQuery[ValBoundTable]
  def readAll(): DBIO[Seq[Int]] = valBoundQuery.result
}

// ── Nested Table in companion object ────────────────────────────────────────
//
// Table subclass and TableQuery defined inside a module (object). The scanner
// includes nestedClassesInModules when building the table class map.

object NestedTableRepo {
  class NestedObjTable(tag: Tag) extends Table[(Int, String)](tag, "nested_obj_table") {
    def id = column[Int]("id", O.PrimaryKey)
    def value = column[String]("value")
    def * = (id, value)
  }
  val nestedQuery = TableQuery[NestedObjTable]
  def readNested(): DBIO[Seq[(Int, String)]] = nestedQuery.result
}

// ── Parameterized factory: backward param propagation ───────────────────────
//
// A factory `apply(tableName: String)` creates an anonymous class whose inner
// Table subclass uses the parameter as its table name.
//
// • SingleSiteUser calls the factory with a single literal "param_single".
// • MultiSiteUserA and MultiSiteUserB each call it with a distinct literal.
//
// The scanner resolves all call-site literals via ParamValueIndex and emits
// one integration per resolved table name.

trait SingleSiteRepo {
  def get(): DBIO[Seq[Int]]
  def put(id: Int): DBIO[Int]
}

object SingleSiteRepo {
  def apply(tableName: String): SingleSiteRepo = {
    class DynTable(tag: Tag) extends Table[Int](tag, tableName) {
      def id = column[Int]("id", O.PrimaryKey)
      def * = id
    }
    val q = TableQuery[DynTable]
    new SingleSiteRepo {
      def get()        = q.result
      def put(id: Int) = q.insertOrUpdate(id)
    }
  }
}

class SingleSiteUser {
  private val repo = SingleSiteRepo("param_single")
  def getRow()       = repo.get()
  def putRow(id: Int) = repo.put(id)
}

class MultiSiteUserA {
  private val repo = SingleSiteRepo("param_multi_a")
  def getRow()       = repo.get()
}

class MultiSiteUserB {
  private val repo = SingleSiteRepo("param_multi_b")
  def getRow()       = repo.get()
}

// ── InsertActionComposerImpl pattern ────────────────────────────────────────
//
// Simulates the InsertActionComposerImpl type that slick-pg and Slick core use
// for insertOrUpdate/+= operations. The scanner matches types whose FQN ends
// with "InsertActionComposerImpl" via fqnEndsWith. This mock class exercises
// that code path without requiring slick-pg as a dependency.

class TestInsertActionComposerImpl[T](query: TableQuery[? <: Table[T]]) {
  def insertOrUpdate(value: T): DBIO[Int] = query.insertOrUpdate(value)
}

class ComposerRepo {
  import SlickTables.*
  def upsertViaComposer(id: Long, balance: BigDecimal): DBIO[Int] =
    new TestInsertActionComposerImpl(accountBalances).insertOrUpdate((id, balance))
}
