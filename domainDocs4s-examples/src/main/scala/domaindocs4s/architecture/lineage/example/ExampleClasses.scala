package domaindocs4s.architecture.lineage.example

import doobie.*
import doobie.implicits.*
import cats.effect.IO
import io.grpc.Metadata
import domaindocs4s.architecture.lineage.example.grpc.user_service as userGrpc
import domaindocs4s.architecture.lineage.example.grpc.rate_service as rateGrpc

// ============================================================================
// Example service layers with real doobie + fs2-grpc for TASTy scanning.
//
// Architecture: UserGrpcApi -> UserService -> UserRepo -> Database
//               UserGrpcApi -> RateServiceFs2Grpc (gRPC client)
//
// UserRepo uses real doobie: sql"..." interpolation, .query[T].unique,
// .update.run, etc. The TASTy scanner detects these patterns.
// UserGrpcApi implements UserServiceFs2Grpc (server) and calls
// RateServiceFs2Grpc (client). The gRPC scanner detects these patterns.
// ============================================================================

// ── Domain types ─────────────────────────────────────────────────────────────

case class Transaction(id: Long, userId: Long, amount: BigDecimal, description: String)
object Transaction {
  given Read[Transaction]  = Read.derived
  given Write[Transaction] = Write.derived
}

// ============================================================================
// Service layers — real method bodies that produce inspectable TASTy
// ============================================================================

/** Repository — direct database access via doobie queries. */
class UserRepo {

  def getBalance(userId: Long): ConnectionIO[BigDecimal] =
    sql"SELECT balance FROM users WHERE id = $userId".query[BigDecimal].unique

  def getTransactions(userId: Long): ConnectionIO[List[Transaction]] =
    sql"SELECT id, user_id, amount, description FROM transactions WHERE user_id = $userId"
      .query[Transaction]
      .to[List]

  def insertTransaction(tx: Transaction): ConnectionIO[Int] =
    sql"INSERT INTO transactions (user_id, amount, description) VALUES (${tx.userId}, ${tx.amount}, ${tx.description})".update.run

  def updateBalance(userId: Long, newBalance: BigDecimal): ConnectionIO[Int] =
    sql"UPDATE users SET balance = $newBalance WHERE id = $userId".update.run

  def streamTransactions(userId: Long): fs2.Stream[ConnectionIO, Transaction] =
    sql"SELECT id, user_id, amount, description FROM transactions WHERE user_id = $userId"
      .query[Transaction]
      .stream
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

/** Event publisher — publishes domain events to Kafka.
  *
  * The actual Kafka interaction is library-specific (fs2-kafka, pekko-kafka, etc.) and can't be auto-detected from TASTy. Use LineageAdjustments to
  * declare the Kafka integration: LineageAdjustments.builder .method[EventPublisher](_.publishDeposit).writes.kafka("user.deposit-events") .build
  */
class EventPublisher {
  def publishDeposit(@scala.annotation.unused userId: Long, @scala.annotation.unused amount: BigDecimal): IO[Unit] = IO.unit
}

/** Handler that processes events and writes to DB via a repository. Mimics the Fs2Projection handler pattern (handler wraps a repository).
  */
class BalanceHandler(val repo: UserRepo) {
  def process(userId: Long): ConnectionIO[Int] =
    repo.updateBalance(userId, BigDecimal(0))
}

/** Singleton object with val handler — mimics the Fs2Projection pattern. The call graph should detect that this object's val body constructs a
  * BalanceHandler, linking to its methods and transitively to UserRepo.
  */
object BalanceProjection {
  val handler: BalanceHandler = new BalanceHandler(new UserRepo)
}

/** Demonstrates doobie detection without ConnectionIO return type. The method contains sql"...".query[T].unique but returns IO[T] after
  * .transact(xa). The scanner should detect the doobie pattern regardless of the method's return type.
  */
class DirectDbAccess(xa: Transactor[IO]) {
  def getBalanceIO(userId: Long): IO[BigDecimal] =
    sql"SELECT balance FROM users WHERE id = $userId".query[BigDecimal].unique.transact(xa)
}

/** Demonstrates doobie detection inside a val initializer. The val body contains sql"...".query[T].unique — the scanner should find it because
  * SymbolUsageFinder walks val bodies in addition to def bodies.
  */
class InlineQueryHolder {
  val activeUserCount: ConnectionIO[Long] =
    sql"SELECT count(*) FROM users WHERE active = true".query[Long].unique
}

/** Demonstrates field.method() call detection inside val bodies. The val `defaultBalance` calls `repo.getBalance(0L)` — the call graph should detect
  * this as a field.method() call inside a val initializer.
  */
class CachedService(val repo: UserRepo) {
  val defaultBalance: ConnectionIO[BigDecimal] = repo.getBalance(0L)
}

/** Demonstrates constructor call detection inside def methods. The method `createHandler` calls `new BalanceHandler(repo)` — the call graph should
  * detect this as a constructor call and link to BalanceHandler's methods.
  */
class ServiceFactory(val repo: UserRepo) {
  def createHandler(): BalanceHandler = new BalanceHandler(repo)
}

/** Demonstrates batch Update[Row](sql).updateMany detection. The scanner should detect the `Update[Row](sql).updateMany(data)` pattern and extract
  * the table name from the SQL string (even when stored in a val).
  */
class BatchUpdateRepo {

  case class Row(userId: Long, amount: BigDecimal)
  given Write[Row] = Write.derived

  def batchInsert(rows: List[Row]): ConnectionIO[Int] = {
    val sql = "INSERT INTO daily_balance_change (user_id, amount) VALUES (?, ?)"
    Update[Row](sql).updateMany(rows)
  }
}

/** Demonstrates doobie detection inside if/else branches. The scanner must recurse into If tree branches (not drop them at `case _ =>`).
  */
class ConditionalUpdateRepo {

  case class Row(id: Long, value: String)
  given Write[Row] = Write.derived

  def upsertIfNotEmpty(rows: List[Row]): ConnectionIO[Unit] =
    if (rows.isEmpty) cats.Applicative[ConnectionIO].unit
    else {
      val sql = "INSERT INTO conditional_table (id, value) VALUES (?, ?)"
      Update[Row](sql).updateMany(rows).map(_ => ())
    }
}

/** Demonstrates doobie detection inside match branches. The scanner must recurse into Match tree cases (not drop them at `case _ =>`).
  */
class MatchUpdateRepo {

  def upsertByType(kind: String, value: Int): ConnectionIO[Int] = kind match {
    case "insert" =>
      sql"INSERT INTO match_table (value) VALUES ($value)".update.run
    case "update" =>
      sql"UPDATE match_table SET value = $value WHERE value = 0".update.run
    case _        =>
      sql"SELECT count(*) FROM match_table".query[Int].unique
  }
}

/** Demonstrates SQL keyword filtering in table name extraction. Uses a subquery pattern where `FROM unnest(...)` appears before `FROM
  * keyword_test_table`. The scanner should skip `unnest` (a SQL keyword) and extract `keyword_test_table`.
  */
class UnnestQueryRepo {

  def getExpanded(userId: Long): ConnectionIO[BigDecimal] =
    sql"SELECT sum(v) FROM unnest(ARRAY[1,2,3]) AS v UNION ALL SELECT balance FROM keyword_test_table WHERE user_id = $userId"
      .query[BigDecimal]
      .unique
}

/** Demonstrates doobie detection with fr"..." Fragment interpolator. The fr"..." interpolator produces a Fragment (not Fragment0 like sql"..."). The
  * scanner should detect fr"...".update.run as Write and fr"...".query[T] as Read. Also tests .stripMargin chaining:
  * fr"""...""".stripMargin.update.run.
  */
class FragmentRepo {

  def upsert(userId: Long, amount: BigDecimal): ConnectionIO[Int] =
    fr"""INSERT INTO fr_test_table (user_id, amount)
        |VALUES ($userId, $amount)
        |ON CONFLICT (user_id) DO UPDATE SET amount = $amount""".stripMargin.update.run

  def deleteUser(userId: Long): ConnectionIO[Int] =
    fr"DELETE FROM fr_test_table WHERE user_id = $userId".update.run

  def getAmount(userId: Long): ConnectionIO[Option[BigDecimal]] =
    fr"SELECT amount FROM fr_test_table WHERE user_id = $userId".query[BigDecimal].option

  /** SQL keyword on a subsequent line after `|` margin character. Without stripMargin in SqlUtils.sqlFrom, the `|` blocks regex matching.
    */
  def upsertMargin(userId: Long, amount: BigDecimal): ConnectionIO[Int] =
    fr"""
        |INSERT INTO
        |  fr_test_table (user_id, amount)
        |VALUES ($userId, $amount)
        |ON CONFLICT (user_id) DO UPDATE SET amount = $amount
        |""".stripMargin.update.run
}

/** Demonstrates trait + companion object with anonymous class containing doobie queries. Mimics the pattern: trait with abstract methods, companion
  * object `apply()` creates anonymous class implementing the trait. Both sql"..." (Read) and fr"..." (Write) are used in the anonymous class. The
  * scanner should find both, attributed to `TraitRepo`.
  *
  * Also demonstrates:
  *   - Non-val constructor parameter in the consumer class (`TraitRepoConsumer`)
  *   - Write call going through a local def
  *   - The call graph and ShowClass promotion should propagate both Read and Write
  */
trait TraitRepo {
  def getItem(id: Long): ConnectionIO[Option[BigDecimal]]
  def upsertItem(id: Long, value: BigDecimal): ConnectionIO[Int]
}

object TraitRepo {
  def apply(): TraitRepo = new TraitRepo {
    override def getItem(id: Long): ConnectionIO[Option[BigDecimal]] =
      sql"SELECT value FROM trait_repo_table WHERE id = $id".query[BigDecimal].option

    override def upsertItem(id: Long, value: BigDecimal): ConnectionIO[Int] =
      fr"""INSERT INTO trait_repo_table (id, value)
          |VALUES ($id, $value)
          |ON CONFLICT (id) DO UPDATE SET value = $value""".stripMargin.update.run
  }
}

/** Consumer that takes TraitRepo as a non-val constructor parameter. Calls getItem directly and upsertItem through a local def. Mimics
  * AdvancedPortfolioReader → AbpCacheRepository pattern.
  */
class TraitRepoConsumer(traitRepo: TraitRepo) {
  def readAndWrite(id: Long): ConnectionIO[Option[BigDecimal]] = {
    def doUpsert(v: BigDecimal): ConnectionIO[Int] =
      traitRepo.upsertItem(id, v)
    for {
      existing <- traitRepo.getItem(id)
      _        <- doUpsert(existing.getOrElse(BigDecimal(0)))
    } yield existing
  }
}

/** Entry point that uses TraitRepoConsumer. Mimics LedgerServiceAPIImpl. When ShowClass is used and only TraitRepoEntryPoint is shown, integrations
  * from TraitRepo should be promoted here.
  */
class TraitRepoEntryPoint(val consumer: TraitRepoConsumer) {
  def handle(id: Long): ConnectionIO[Option[BigDecimal]] =
    consumer.readAndWrite(id)
}

/** Demonstrates nested case class inside companion object (factory pattern).
  *
  * The sealed trait defines abstract methods. The companion contains a case class `Impl` with constructor fields that have doobie queries. A consumer
  * creates `Impl` locally via the case class constructor (which TASTy may represent as companion `apply`).
  *
  * The call graph must:
  *   1. Discover the nested `Impl` class inside the companion object 2. Detect constructor calls to nested case classes 3. Trace field.method() calls
  *      inside `Impl` to `UserRepo`
  */
sealed trait NestedImplService {
  def fetchBalance(userId: Long): ConnectionIO[BigDecimal]
}

object NestedImplService {
  case class Impl(val repo: UserRepo) extends NestedImplService {
    def fetchBalance(userId: Long): ConnectionIO[BigDecimal] =
      repo.getBalance(userId)
  }
}

/** Consumer that creates NestedImplService.Impl locally. Mimics `dumpOperatorAssetsJob.generateStatement` creating
  * `OperatorLedgerStatement.Impl(...)`.
  */
class NestedImplConsumer {
  def run(userId: Long): ConnectionIO[BigDecimal] = {
    val svc = NestedImplService.Impl(new UserRepo)
    svc.fetchBalance(userId)
  }
}

/** Demonstrates type alias with intersection type for isOrInheritsFrom matching. Mimics the Pekko ReadJournal pattern where a type alias combines
  * multiple query traits: type JournalRead = ReadJournal & CurrentEventsByPersistenceIdQuery & EventsByTagQuery The
  * TypeMatcher.isOrInheritsFrom("BaseQueryTrait") must resolve the type alias, decompose the intersection type (represented as AppliedType(scala.&)
  * in TASTy), and find the matching component.
  */
trait BaseQueryTrait {
  def runQuery(id: Long): String
}

trait ExtendedQueryTrait {
  def runExtended(id: Long): String
}

object TypeAliasService {
  type CombinedQuery = BaseQueryTrait & ExtendedQueryTrait
}

class TypeAliasConsumer(val queryApi: TypeAliasService.CombinedQuery) {
  def doQuery(id: Long): String    = queryApi.runQuery(id)
  def doExtended(id: Long): String = queryApi.runExtended(id)
}

/** Demonstrates no-arg method/property call detection. `provider.allTransactions` is a parameterless def — in TASTy it produces Select(Ident(field),
  * method) without an Apply wrapper. The call graph should detect this bare Select pattern.
  */
trait StreamProvider                                  {
  def allTransactions: ConnectionIO[List[Transaction]]
}
class NoArgCallConsumer(val provider: StreamProvider) {
  def getAll: ConnectionIO[List[Transaction]] = provider.allTransactions
}

/** Demonstrates imported function calls from nested objects.
  *
  * The outer object imports methods from a nested inner object and calls them. The call graph must:
  *   1. Discover nested module classes (objects inside objects) 2. Detect imported function calls (Apply(Ident(name), args)) 3. Trace
  *      parameter.method() calls inside the nested object methods
  */
object ImportedCallJob {
  import ImportedCallJob.Helpers.processBalance

  def run(userId: Long): ConnectionIO[BigDecimal] = {
    val svc = new UserService(new UserRepo)
    processBalance(svc, userId)
  }

  object Helpers {
    def processBalance(svc: UserService, userId: Long): ConnectionIO[BigDecimal] =
      svc.getBalance(userId)
  }
}

/** Demonstrates module/singleton object call resolution. The MethodCallCollector's `addModuleCall` resolves `DataProcessor.processData()` and
  * `DataProcessor.transformData()` via the Ident's TermRef, linking to the singleton object's methods in the call graph.
  */
object DataProcessor {
  def processData(): Unit   = ()
  def transformData(): Unit = ()
}

class ModuleCallerService {
  def runProcessing(): Unit = {
    DataProcessor.processData()
    DataProcessor.transformData()
  }
}

/** Demonstrates SymbolUsageFinder scanning of nested classes inside companion objects. The `InnerDoobieRepo` is a class nested inside
  * `CompanionWithNested` object. Without the nestedClassesInModules fix, the doobie query would not be detected.
  */
object CompanionWithNested {
  class InnerDoobieRepo {
    def findInner(id: Long): ConnectionIO[Option[BigDecimal]] =
      sql"SELECT value FROM nested_finder_table WHERE id = $id".query[BigDecimal].option
  }
}

/** gRPC API — entry point, delegates to service.
  *
  * Implements UserServiceFs2Grpc (server exposure) and consumes RateServiceFs2Grpc (client usage). The gRPC scanner detects both.
  */
class UserGrpcApi(
    val service: UserService,
    val rateClient: rateGrpc.RateServiceFs2Grpc[IO, Metadata],
    val publisher: EventPublisher,
    xa: Transactor[IO],
) extends userGrpc.UserServiceFs2Grpc[IO, Metadata] {

  def getBalance(request: userGrpc.GetBalanceRequest, ctx: Metadata): IO[userGrpc.GetBalanceResponse] =
    service
      .getBalance(request.userId)
      .transact(xa)
      .map(b => userGrpc.GetBalanceResponse(b.toString))

  def deposit(request: userGrpc.DepositRequest, ctx: Metadata): IO[userGrpc.DepositResponse] =
    for {
      rate <- rateClient.getRate(rateGrpc.GetRateRequest(request.currency), ctx)
      _    <- service.deposit(request.userId, BigDecimal(request.amount)).transact(xa)
      _    <- publisher.publishDeposit(request.userId, BigDecimal(request.amount))
    } yield userGrpc.DepositResponse(true)

  def getHistory(request: userGrpc.GetHistoryRequest, ctx: Metadata): IO[userGrpc.GetHistoryResponse] =
    service
      .getHistory(request.userId)
      .transact(xa)
      .map(txs => userGrpc.GetHistoryResponse(txs.map(t => userGrpc.TransactionProto(t.id, t.userId, t.amount.toString, t.description))))
}
